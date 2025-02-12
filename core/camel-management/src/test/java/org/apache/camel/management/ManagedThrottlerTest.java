/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.management;

import java.util.concurrent.Callable;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.management.Attribute;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.apache.camel.builder.NotifyBuilder;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.mock.MockEndpoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.apache.camel.management.DefaultManagementObjectNameStrategy.TYPE_PROCESSOR;
import static org.apache.camel.management.DefaultManagementObjectNameStrategy.TYPE_ROUTE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisabledOnOs(OS.AIX)
@DisabledIfSystemProperty(named = "ci.env.name", matches = "github.com", disabledReason = "Flaky on GitHub Actions")
public class ManagedThrottlerTest extends ManagementTestSupport {

    @Test
    public void testManageThrottler() throws Exception {
        getMockEndpoint("mock:result").expectedMessageCount(10);

        // Send in a first batch of 10 messages and check that the endpoint
        // gets them.  We'll check the total time of the second and third
        // batches as it seems that there is some time required to prime
        // things, which can vary significantly... particularly on slower
        // machines.
        for (int i = 0; i < 10; i++) {
            template.sendBody("direct:start", "Message " + i);
        }

        assertMockEndpointsSatisfied();

        // get the stats for the route
        MBeanServer mbeanServer = getMBeanServer();

        // get the object name for the delayer
        ObjectName throttlerName
                = getCamelObjectName(TYPE_PROCESSOR, "mythrottler");

        // use route to get the total time
        ObjectName routeName = getCamelObjectName(TYPE_ROUTE, "route1");

        // reset the counters
        mbeanServer.invoke(routeName, "reset", null, null);

        // send in 10 messages
        for (int i = 0; i < 10; i++) {
            template.sendBody("direct:start", "Message " + i);
        }

        Long completed = (Long) mbeanServer.getAttribute(routeName, "ExchangesCompleted");
        assertEquals(10, completed.longValue());

        Long total = (Long) mbeanServer.getAttribute(routeName, "TotalProcessingTime");

        // 10 * delay (100) + tolerance (200)
        assertTrue(total < 1200, "Should take at most 1.2 sec: was " + total);

        // change the throttler using JMX
        mbeanServer.setAttribute(throttlerName, new Attribute("MaximumConcurrentRequests", (long) 2));

        // reset the counters
        mbeanServer.invoke(routeName, "reset", null, null);

        // send in another 10 messages
        for (int i = 0; i < 10; i++) {
            template.sendBody("direct:start", "Message " + i);
        }

        Long requests = (Long) mbeanServer.getAttribute(throttlerName, "MaximumConcurrentRequests");
        assertNotNull(requests);
        assertEquals(2, requests.longValue());

        completed = (Long) mbeanServer.getAttribute(routeName, "ExchangesCompleted");
        assertEquals(10, completed.longValue());
        total = (Long) mbeanServer.getAttribute(routeName, "TotalProcessingTime");

        // 10 * delay (100) + tolerance (200)
        assertTrue(total < 1200, "Should take at most 1.2 sec: was " + total);
    }

    @DisabledOnOs(OS.WINDOWS)
    @Test
    public void testThrottleVisableViaJmx() throws Exception {
        // get the stats for the route
        MBeanServer mbeanServer = getMBeanServer();

        // use route to get the total time
        ObjectName routeName = getCamelObjectName(TYPE_ROUTE, "route2");

        // reset the counters
        mbeanServer.invoke(routeName, "reset", null, null);

        getMockEndpoint("mock:end").expectedMessageCount(10);

        NotifyBuilder notifier = new NotifyBuilder(context).from("seda:throttleCount").whenReceived(5).create();

        for (int i = 0; i < 10; i++) {
            template.sendBody("seda:throttleCount", "Message " + i);
        }

        assertTrue(notifier.matches(2, TimeUnit.SECONDS));
        assertMockEndpointsSatisfied();

        Long completed = (Long) mbeanServer.getAttribute(routeName, "ExchangesCompleted");
        assertEquals(10, completed.longValue());
    }

    @DisabledOnOs(OS.WINDOWS)
    @Test
    public void testThrottleAsyncVisableViaJmx() throws Exception {
        // get the stats for the route
        MBeanServer mbeanServer = getMBeanServer();

        // use route to get the total time
        ObjectName routeName = getCamelObjectName(TYPE_ROUTE, "route3");

        // reset the counters
        mbeanServer.invoke(routeName, "reset", null, null);

        getMockEndpoint("mock:endAsync").expectedMessageCount(10);

        // we pick '5' because we are right in the middle of the number of messages
        // that have been and reduces any race conditions to minimal...
        NotifyBuilder notifier = new NotifyBuilder(context).from("seda:throttleCountAsync").whenReceived(5).create();

        for (int i = 0; i < 10; i++) {
            template.sendBody("seda:throttleCountAsync", "Message " + i);
        }

        assertTrue(notifier.matches(2, TimeUnit.SECONDS));
        assertMockEndpointsSatisfied();

        Long completed = (Long) mbeanServer.getAttribute(routeName, "ExchangesCompleted");
        assertEquals(10, completed.longValue());
    }

    @DisabledOnOs(OS.WINDOWS)
    @Test
    public void testThrottleAsyncExceptionVisableViaJmx() throws Exception {
        // get the stats for the route
        MBeanServer mbeanServer = getMBeanServer();

        // use route to get the total time
        ObjectName routeName = getCamelObjectName(TYPE_ROUTE, "route4");

        // reset the counters
        mbeanServer.invoke(routeName, "reset", null, null);

        getMockEndpoint("mock:endAsyncException").expectedMessageCount(10);

        NotifyBuilder notifier = new NotifyBuilder(context).from("seda:throttleCountAsyncException").whenReceived(5).create();

        for (int i = 0; i < 10; i++) {
            template.sendBody("seda:throttleCountAsyncException", "Message " + i);
        }

        assertTrue(notifier.matches(2, TimeUnit.SECONDS));
        assertMockEndpointsSatisfied();

        // give a sec for exception handling to finish..
        Thread.sleep(500);

        // since all exchanges ended w/ exception, they are not completed
        Long completed = (Long) mbeanServer.getAttribute(routeName, "ExchangesCompleted");
        assertEquals(0, completed.longValue());
    }

    @Test
    public void testRejectedExecution() throws Exception {
        // when delaying async, we can possibly fill up the execution queue
        //. which would through a RejectedExecutionException.. we need to make
        // sure that the delayedCount/throttledCount doesn't leak

        // get the stats for the route
        MBeanServer mbeanServer = getMBeanServer();

        // use route to get the total time
        ObjectName routeName = getCamelObjectName(TYPE_ROUTE, "route2");

        // reset the counters
        mbeanServer.invoke(routeName, "reset", null, null);

        MockEndpoint mock = getMockEndpoint("mock:endAsyncReject");
        // only one message (the first one) should get through because the rest should get delayed
        mock.expectedMessageCount(1);

        MockEndpoint exceptionMock = getMockEndpoint("mock:rejectedExceptionEndpoint1");
        exceptionMock.expectedMessageCount(9);

        for (int i = 0; i < 10; i++) {
            template.sendBody("seda:throttleCountRejectExecution", "Message " + i);
        }

        assertMockEndpointsSatisfied();
    }

    @Test
    public void testRejectedExecutionCallerRuns() throws Exception {
        // when delaying async, we can possibly fill up the execution queue
        //. which would through a RejectedExecutionException.. we need to make
        // sure that the delayedCount/throttledCount doesn't leak

        // get the stats for the route
        MBeanServer mbeanServer = getMBeanServer();

        // use route to get the total time
        ObjectName routeName = getCamelObjectName(TYPE_ROUTE, "route2");

        // reset the counters
        mbeanServer.invoke(routeName, "reset", null, null);

        MockEndpoint mock = getMockEndpoint("mock:endAsyncRejectCallerRuns");
        // only one message (the first one) should get through because the rest should get delayed
        mock.expectedMessageCount(10);

        MockEndpoint exceptionMock = getMockEndpoint("mock:rejectedExceptionEndpoint");
        exceptionMock.expectedMessageCount(0);

        for (int i = 0; i < 10; i++) {
            template.sendBody("seda:throttleCountRejectExecutionCallerRuns", "Message " + i);
        }

        assertMockEndpointsSatisfied();
    }

    @Override
    protected RouteBuilder createRouteBuilder() throws Exception {
        final ScheduledExecutorService badService = new ScheduledThreadPoolExecutor(1) {
            @Override
            public <V> ScheduledFuture<V> schedule(Callable<V> command, long delay, TimeUnit unit) {
                throw new RejectedExecutionException();
            }
        };

        return new RouteBuilder() {
            @Override
            public void configure() throws Exception {
                from("direct:start").id("route1")
                        .to("log:foo")
                        .throttle(10).id("mythrottler")
                        .delay(100)
                        .to("mock:result");

                from("seda:throttleCount").id("route2")
                        .throttle(1).id("mythrottler2").delay(250)
                        .to("mock:end");

                from("seda:throttleCountAsync").id("route3")
                        .throttle(1).asyncDelayed().id("mythrottler3").delay(250)
                        .to("mock:endAsync");

                from("seda:throttleCountAsyncException").id("route4")
                        .throttle(1).asyncDelayed().id("mythrottler4").delay(250)
                        .to("mock:endAsyncException")
                        .process(exchange -> {
                            throw new RuntimeException("Fail me");
                        });
                from("seda:throttleCountRejectExecutionCallerRuns").id("route5")
                        .onException(RejectedExecutionException.class).to("mock:rejectedExceptionEndpoint1").end()
                        .throttle(1)
                        .asyncDelayed()
                        .executorService(badService)
                        .callerRunsWhenRejected(true)
                        .id("mythrottler5")
                        .delay(250)
                        .to("mock:endAsyncRejectCallerRuns");

                from("seda:throttleCountRejectExecution").id("route6")
                        .onException(RejectedExecutionException.class).to("mock:rejectedExceptionEndpoint1").end()
                        .throttle(1)
                        .asyncDelayed()
                        .executorService(badService)
                        .callerRunsWhenRejected(false)
                        .id("mythrottler6")
                        .delay(250)
                        .to("mock:endAsyncReject");
            }
        };
    }

}
