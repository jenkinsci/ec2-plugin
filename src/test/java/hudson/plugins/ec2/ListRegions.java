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


/**
 * @author Kohsuke Kawaguchi
 */
public class ListRegions {
    public static void main(String[] args) throws Exception {
//        Jec2 ec2 = new Jec2("...","...");
//        final List<RegionInfo> regions = ec2.describeRegions(null);
//        for (RegionInfo r : regions)
//            System.out.println(r);
        /*
            AvailabilityZone[name=eu-west-1, url=ec2.eu-west-1.amazonaws.com]
            AvailabilityZone[name=us-east-1, url=ec2.us-east-1.amazonaws.com]
            AvailabilityZone[name=ap-northeast-1, url=ec2.ap-northeast-1.amazonaws.com]
            AvailabilityZone[name=us-west-1, url=ec2.us-west-1.amazonaws.com]
            AvailabilityZone[name=ap-southeast-1, url=ec2.ap-southeast-1.amazonaws.com]
         */
    }
}
