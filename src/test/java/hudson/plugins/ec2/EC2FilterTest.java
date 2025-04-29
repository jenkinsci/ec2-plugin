/*
 * The MIT License
 *
 * Copyright © 2020 Endless OS Foundation LLC
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

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.model.Filter;

class EC2FilterTest {

    @Test
    void testSingleValue() {
        EC2Filter ec2Filter = new EC2Filter("name", "value");
        assertEquals("name", ec2Filter.getName());
        assertEquals("value", ec2Filter.getValues());
        assertEquals("EC2Filter{name=\"name\", values=\"value\"}", ec2Filter.toString());
        Filter filter = ec2Filter.toFilter();
        assertNotNull(filter);
        assertEquals("name", filter.name());
        assertEquals(List.of("value"), filter.values());
    }

    @Test
    void testMultiValue() {
        EC2Filter ec2Filter = new EC2Filter("name", "value1 'value 2'");
        assertEquals("name", ec2Filter.getName());
        assertEquals("value1 'value 2'", ec2Filter.getValues());
        assertEquals("EC2Filter{name=\"name\", values=\"value1 'value 2'\"}", ec2Filter.toString());
        Filter filter = ec2Filter.toFilter();
        assertNotNull(filter);
        assertEquals("name", filter.name());
        assertEquals(Arrays.asList("value1", "value 2"), filter.values());
    }

    @Test
    void testEmptyValue() {
        EC2Filter ec2Filter = new EC2Filter("", "");
        assertEquals("", ec2Filter.getName());
        assertEquals("", ec2Filter.getValues());
        assertEquals("EC2Filter{name=\"\", values=\"\"}", ec2Filter.toString());
        Filter filter = ec2Filter.toFilter();
        assertNotNull(filter);
        assertEquals("", filter.name());
        assertEquals(Collections.emptyList(), filter.values());
    }

    @Test
    void testNullValue() {
        assertThrows(NullPointerException.class, () -> new EC2Filter(null, "value"));
        assertThrows(NullPointerException.class, () -> new EC2Filter("name", null));
        assertThrows(NullPointerException.class, () -> new EC2Filter(null, null));
    }

    @Test
    void testEquals() {
        EC2Filter a;
        EC2Filter b;

        a = new EC2Filter("", "");
        assertNotEquals(null, a);

        a = new EC2Filter("", "");
        assertNotEquals(new Object(), a);

        a = new EC2Filter("", "");
        b = new EC2Filter("", "");
        assertEquals(a, b);

        a = new EC2Filter("", "");
        b = new EC2Filter("", "  ");
        assertEquals(a, b);

        a = new EC2Filter("foo", "value");
        b = new EC2Filter("bar", "value");
        assertNotEquals(a, b);

        a = new EC2Filter("name", "value");
        b = new EC2Filter("name", "value");
        assertEquals(a, b);

        a = new EC2Filter("name", "value");
        b = new EC2Filter(" name ", "value");
        assertNotEquals(a, b);

        a = new EC2Filter("name", "value1");
        b = new EC2Filter("name", "value2");
        assertNotEquals(a, b);

        a = new EC2Filter("name", " value ");
        b = new EC2Filter("name", "'value'");
        assertEquals(a, b);

        a = new EC2Filter("name", "value1 value2");
        b = new EC2Filter("name", " value1  'value2' ");
        assertEquals(a, b);

        a = new EC2Filter("name", "s p a c e s");
        b = new EC2Filter("name", "'s p a c e s'");
        assertNotEquals(a, b);

        a = new EC2Filter("name", "s\\ p\\ a\\ c\\ e\\ s");
        b = new EC2Filter("name", "'s p a c e s'");
        assertEquals(a, b);

        a = new EC2Filter("name", "a\\'quote");
        b = new EC2Filter("name", "\"a'quote\"");
        assertEquals(a, b);
    }
}
