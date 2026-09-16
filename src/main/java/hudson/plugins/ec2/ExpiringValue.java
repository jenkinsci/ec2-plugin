/*
 * The MIT License
 *
 * Copyright (c) 2004-, Kohsuke Kawaguchi, Sun Microsystems, Inc., and a number of other of contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.plugins.ec2;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.time.Clock;

/**
 * Holds something read from EC2 that is the same on every launch, so that a burst of launches does
 * not re-read it once per template per attempt.
 *
 * <p>What a template needs before it can call {@code RunInstances} — its image, the key pair, the
 * ids behind its security group names — is configuration rather than state, and each one costs a
 * round trip to EC2 in front of the launch. Those round trips are serial and they are what a queued
 * build waits through before its instance is even asked for.
 *
 * <p>Expiry rather than a permanent cache because a template may name its image by search
 * attributes instead of an id, where the answer legitimately changes when a new AMI is published.
 */
final class ExpiringValue<T> {

    private final long ttlMillis;

    private final Clock clock;

    private volatile T value;

    private volatile long expiresAt;

    ExpiringValue(long ttlMillis) {
        this(ttlMillis, Clock.systemUTC());
    }

    ExpiringValue(long ttlMillis, Clock clock) {
        this.ttlMillis = ttlMillis;
        this.clock = clock;
    }

    /**
     * @return the value held, or {@code null} if nothing has been put here or what was put has
     *     expired, in which case the caller reads it from EC2 and {@link #put} it back.
     */
    @CheckForNull
    T get() {
        return clock.millis() < expiresAt ? value : null;
    }

    /**
     * @return the value given, so a caller can return it in the same statement that caches it.
     */
    T put(T value) {
        this.value = value;
        this.expiresAt = clock.millis() + ttlMillis;
        return value;
    }

    /** Drops what is held, so the next read goes to EC2. */
    void clear() {
        expiresAt = 0;
        value = null;
    }
}
