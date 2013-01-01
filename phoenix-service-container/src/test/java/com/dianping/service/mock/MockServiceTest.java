package com.dianping.service.mock;

import junit.framework.Assert;

import org.junit.Test;
import org.unidal.lookup.ComponentTestCase;

import com.dianping.service.ServiceContainer;
import com.dianping.service.ServiceNotAvailableException;
import com.dianping.service.spi.ServiceBinding;
import com.dianping.service.spi.ServiceRegistry;
import com.dianping.service.spi.internal.DefaultServiceBinding;

public class MockServiceTest extends ComponentTestCase {
	@Test
	public void testMockWithoutBinding() throws Exception {
		ServiceContainer container = lookup(ServiceContainer.class);

		try {
			container.lookup(MockService.class);

			Assert.fail("ServiceNotAvailableException should be thrown!");
		} catch (ServiceNotAvailableException e) {
			// expected
		}
	}

	@Test
	public void testMockWithLateBinding() throws Exception {
		ServiceContainer container = lookup(ServiceContainer.class);
		ServiceRegistry registry = lookup(ServiceRegistry.class);
		ServiceBinding binding = new DefaultServiceBinding(null);

		registry.setServiceBinding(MockService.class, null, binding);

		MockService service = container.lookup(MockService.class);

		Assert.assertEquals("Hello, world!", service.hello("world"));
	}
}
