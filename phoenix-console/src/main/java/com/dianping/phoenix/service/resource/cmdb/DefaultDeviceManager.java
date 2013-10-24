package com.dianping.phoenix.service.resource.cmdb;

import java.net.ConnectException;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.codehaus.plexus.logging.LogEnabled;
import org.codehaus.plexus.logging.Logger;
import org.unidal.lookup.annotation.Inject;

import com.dianping.phoenix.configure.ConfigManager;
import com.dianping.phoenix.device.entity.Attribute;
import com.dianping.phoenix.device.entity.Device;
import com.dianping.phoenix.device.entity.Responce;
import com.dianping.phoenix.device.transform.DefaultSaxParser;
import com.dianping.phoenix.service.visitor.DeviceVisitor;
import com.dianping.phoenix.utils.StringUtils;

public class DefaultDeviceManager implements DeviceManager, LogEnabled {

	@Inject
	private ConfigManager m_configManager;

	private Logger m_logger;

	private Responce readCmdb(String url) throws ConnectException {
		try {
			URL cmdbUrl = new URL(url);
			URLConnection conn = cmdbUrl.openConnection();
			conn.setConnectTimeout(1000);
			conn.setReadTimeout(1000);
			conn.connect();
			return DefaultSaxParser.parse(conn.getInputStream());
		} catch (Exception e) {
			m_logger.warn(String.format("Read cmdb failed [%s]", url), e);
			throw new ConnectException(String.format("Read cmdb failed [%s]", url));
		}
	}

	@Override
	public void enableLogging(Logger logger) {
		m_logger = logger;
	}

	@Override
	public List<Device> getDevices(String name) throws ConnectException {
		List<Device> devices = new ArrayList<Device>();
		if (name != null && name.trim().length() > 0) {
			StringBuilder cmdbQuery = new StringBuilder(String.format(m_configManager.getCmdbBaseUrl(),
					"/s?wt=xml&fl=hostname,private_ip,status,rd_duty,env&q=app:%s,"));
			for (String env : m_configManager.getEnvironments()) {
				cmdbQuery.append(String.format("-env:%s,", env));
			}
			Responce responce = readCmdb(cmdbQuery.toString());
			if (responce != null && responce.getDevices() != null) {
				for (Device device : responce.getDevices()) {
					devices.add(device);
				}
			}
		}
		return devices;
	}

	@Override
	public Map<String, Map<String, List<Device>>> getDeviceCatalog() throws ConnectException {
		StringBuilder cmdbQuery = new StringBuilder(String.format(m_configManager.getCmdbBaseUrl(),
				"/s?wt=xml&fl=hostname,private_ip,status,rd_duty,env,app,catalog&q="));
		for (String env : m_configManager.getEnvironments()) {
			cmdbQuery.append(String.format("-env:%s,", env));
		}

		Responce responce = readCmdb(cmdbQuery.toString());
		ExecutorService executor = Executors.newCachedThreadPool();
		boolean finish = false;

		if (responce != null) {
			int pageCount = (int) Math.ceil(responce.getNumfound() / 50.0);
			int threadCount = (int) Math.ceil((pageCount - 1) / 10.0);// 10_pages/thread
			int curPage = 2;
			CountDownLatch latch = new CountDownLatch(threadCount);
			m_logger.info(String.format(
					"########## Cmdb page count:【%d】, thread count:【%d】, latch count:【%d】##########", pageCount,
					threadCount, latch.getCount()));
			while (curPage <= pageCount) {
				executor.execute(new QueryTask(cmdbQuery.toString(), responce, curPage, getEndPageNumber(curPage,
						pageCount), latch));
				curPage += 10;
			}

			try {
				finish = latch.await(m_configManager.getCmdbTimeoutInSecond(), TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				m_logger.error("Error happens when get cmdb infos, lost some infos.", e);
			}
		}
		executor.shutdownNow();

		m_logger.info(String
				.format("########## Read cmdb catalog finished. Except count:【%d】, Total count:【%d】, Finish correctly:【%b】 ##########",
						responce.getNumfound(), responce.getDevices().size(), finish));

		return generateCatalog(responce.getDevices());
	}

	private int getEndPageNumber(int startPageNumber, int totalCount) {
		// 10 pages per thread
		return (startPageNumber + 9) < totalCount ? (startPageNumber + 9) : totalCount;
	}

	private Map<String, Map<String, List<Device>>> generateCatalog(List<Device> devices) {
		Set<String> productSet = m_configManager.getProductSet();

		Map<String, Map<String, List<Device>>> catalog = new LinkedHashMap<String, Map<String, List<Device>>>();
		int ignoreDeviceCount = 0;

		for (Device device : devices) {
			String productName = getAttributeText(device.getAttributes().get(DeviceVisitor.KEY_CATALOG), "none");
			String domainName = getAttributeText(device.getAttributes().get(DeviceVisitor.KEY_APP), "none");
			if (!"none".equals(productName) && !"none".equals(domainName)
					&& (productSet.size() == 0 || productSet.contains(productName))) {
				getDomainFromProduct(getProductFromCatalog(catalog, productName), domainName).add(device);
			} else {
				ignoreDeviceCount++;
			}
		}
		m_logger.info(String.format(
				"########## Ignore some devices due to Product name or domain name is null, count:【%d】##########",
				ignoreDeviceCount));
		return catalog;
	}

	private String getAttributeText(Attribute attribute, String defaultValue) {
		return attribute != null && !StringUtils.isBlank(attribute.getText()) ? attribute.getText() : defaultValue;
	}

	private Map<String, List<Device>> getProductFromCatalog(Map<String, Map<String, List<Device>>> catalog,
			String productName) {
		Map<String, List<Device>> ret = catalog.get(productName);
		if (ret == null) {
			ret = new LinkedHashMap<String, List<Device>>();
			catalog.put(productName, ret);
		}
		return ret;
	}

	private List<Device> getDomainFromProduct(Map<String, List<Device>> product, String domainName) {
		List<Device> ret = product.get(domainName);
		if (ret == null) {
			ret = new ArrayList<Device>();
			product.put(domainName, ret);
		}
		return ret;
	}

	private class QueryTask implements Runnable {
		private String query;
		private Responce all;
		private int startPage;
		private int endPage;
		private CountDownLatch latch;

		public QueryTask(String queryBaseString, Responce responce, int startPage, int endPage, CountDownLatch latch) {
			this.all = responce;
			this.startPage = startPage;
			this.endPage = endPage;
			this.latch = latch;
			this.query = queryBaseString;
		}

		@Override
		public void run() {
			for (int page = startPage; page <= endPage && !Thread.interrupted(); page++) {
				String q = String.format(query + "&page=%d", page);
				m_logger.info(String.format("Querying: [%s]", q));
				try {
					Responce responce = readCmdb(q);
					for (Device device : responce.getDevices()) {
						all.addDevice(device);
					}
				} catch (ConnectException e) {
					m_logger.error("Read cmdb failed.", e);
				}
			}
			latch.countDown();
		}
	}
}
