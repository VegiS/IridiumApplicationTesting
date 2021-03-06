package au.com.agic.apptesting.utils.impl;

import static com.google.common.base.Preconditions.checkArgument;

import au.com.agic.apptesting.constants.Constants;
import au.com.agic.apptesting.exception.ConfigurationException;
import au.com.agic.apptesting.profiles.FileProfileAccess;
import au.com.agic.apptesting.profiles.configuration.Configuration;
import au.com.agic.apptesting.profiles.configuration.UrlMapping;
import au.com.agic.apptesting.utils.ProxyDetails;
import au.com.agic.apptesting.utils.SystemPropertyUtils;
import au.com.agic.apptesting.utils.ThreadDetails;
import au.com.agic.apptesting.utils.ThreadWebDriverMap;

import org.apache.commons.lang3.StringUtils;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.remote.http.HttpClient;
import org.openqa.selenium.remote.internal.ApacheHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.validation.constraints.NotNull;

public class ThreadWebDriverMapImpl implements ThreadWebDriverMap {

	/**
	 * The end of the URL we use to connect remotely to browserstack
	 */
	private static final String URL = "@hub.browserstack.com/wd/hub";
	private static final Logger LOGGER = LoggerFactory.getLogger(ThreadWebDriverMapImpl.class);
	private static final SystemPropertyUtils SYSTEM_PROPERTY_UTILS = new SystemPropertyUtilsImpl();
	private static final FileProfileAccess<Configuration> PROFILE_ACCESS = new FileProfileAccess<>(
		SYSTEM_PROPERTY_UTILS.getProperty(Constants.CONFIGURATION),
		Configuration.class);
	private static final HttpClient.Factory HTTP_CLIENT_FACTORY = new ApacheHttpClient.Factory();
	/**
	 * The mapping between thread ids and the webdrivers that they use for the tests
	 */
	private final Map<String, ThreadDetails> threadIdToCapMap =
		new HashMap<>();
	/**
	 * The browser stack username loaded from configuration
	 */
	private String browserStackUsername;
	/**
	 * The browser stack access token loaded from configuration
	 */
	private String browserStackAccessToken;
	/**
	 * The index of the data set we are going to be testing
	 */
	private int currentDataset;
	/**
	 * The index of the Url we are going to be testing
	 */
	private int currentUrl;
	/**
	 * The index of the capability we are going to be testing
	 */
	private int currentCapability;
	/**
	 * The original list of configurations
	 */
	private List<DesiredCapabilities> originalDesiredCapabilities;
	/**
	 * The list of URLs associated with the application we are testing
	 */
	private List<UrlMapping> originalApplicationUrls;
	/**
	 * The values that can be input into the app
	 */
	private Map<Integer, Map<String, String>> originalDataSets;

	private String reportDirectory;

	public ThreadWebDriverMapImpl() {
		loadBrowserStackSettings();
	}

	/**
	 * Load the browserstack details from configuration
	 */
	private void loadBrowserStackSettings() {
		final Optional<Configuration> profile = PROFILE_ACCESS.getProfile();
		if (profile.isPresent()) {
			browserStackUsername = profile.get().getBrowserstack().getUsername();
			browserStackAccessToken = profile.get().getBrowserstack().getAccessToken();

		} else {
			LOGGER.error("Could not load browserstack config");
		}
	}

	@Override
	public void initialise(
		@NotNull final List<DesiredCapabilities> desiredCapabilities,
		@NotNull final List<UrlMapping> applicationUrls,
		@NotNull final Map<Integer, Map<String, String>> datasets,
		@NotNull final String myReportDirectory,
		@NotNull final List<File> myTempFolders,
		@NotNull final List<ProxyDetails<?>> myProxies) {

		originalDesiredCapabilities = new ArrayList<>(desiredCapabilities);
		originalApplicationUrls = new ArrayList<>(applicationUrls);
		originalDataSets = new HashMap<>(datasets);
		reportDirectory = myReportDirectory;

		/*
			myProxyPort is ignored, because we can setup proxys when running in browserstack
		 */
	}

	@Override
	public synchronized ThreadDetails getDesiredCapabilitiesForThread(@NotNull final String name) {
		try {
			if (threadIdToCapMap.containsKey(name)) {
				return threadIdToCapMap.get(name);
			}

			/*
				Some validation checking
			 */
			if (originalDesiredCapabilities.isEmpty() || originalApplicationUrls.isEmpty()) {
				throw new ConfigurationException("There are no configurations available. "
					+ "Check the configuration profiles have the required information in them");
			}

			/*
				We have allocated our available configurations
			 */
			if (currentUrl >= originalApplicationUrls.size()) {
				throw new ConfigurationException("Configuration pool has been exhausted!");
			}

			/*
				Get the details that the requesting thread will need
			 */
			final DesiredCapabilities desiredCapabilities =
				originalDesiredCapabilities.get(currentCapability);
			final UrlMapping url = originalApplicationUrls.get(currentUrl);
			final Map<String, String> dataSet = originalDataSets.containsKey(currentDataset)
				? new HashMap<>(originalDataSets.get(currentDataset)) : new HashMap<>();

			/*
				Tick over to the next url when all the capabilities have been consumed
			 */
			++currentCapability;
			if (currentCapability >= originalDesiredCapabilities.size()) {

				++currentDataset;
				if (currentDataset >= getMaxDataSets()) {
					currentDataset = 0;
					currentCapability = 0;
					++currentUrl;
				}
			}

			/*
				Associate the new details with the thread
			 */
			final String remoteAddress =
				"http://" + browserStackUsername + ":" + browserStackAccessToken + URL;
			final RemoteWebDriver remoteWebDriver = new RemoteWebDriver(
				new URL(remoteAddress), desiredCapabilities);

			final ThreadDetails threadDetails = new ThreadDetailsImpl(
				url, dataSet, reportDirectory, new ArrayList<>(), remoteWebDriver);

			threadIdToCapMap.put(name, threadDetails);

			return threadDetails;
		} catch (final MalformedURLException ex) {
			/*
				This shouldn't happen
			 */
			throw new ConfigurationException(
				"The url that was built to contact BrowserStack was invalid", ex);
		}
	}

	@Override
	public synchronized int getNumberCapabilities() {
		/*
			Each application is run against each capability
		 */
		return originalDesiredCapabilities.size()
			* originalApplicationUrls.size()
			* Math.max(1, getMaxDataSets());
	}

	@Override
	public List<File> getTempFolders() {
		return null;
	}

	private Integer getMaxDataSets() {
		try {
			final String maxDataSets = SYSTEM_PROPERTY_UTILS.getProperty(
				Constants.NUMBER_DATA_SETS_SYSTEM_PROPERTY);

			if (StringUtils.isNotBlank(maxDataSets)) {
				final Integer maxDataSetsNumber = Integer.parseInt(
					SYSTEM_PROPERTY_UTILS.getProperty(Constants.NUMBER_DATA_SETS_SYSTEM_PROPERTY));

				return Math.min(originalDataSets.size(), maxDataSetsNumber);
			}
		} catch (final NumberFormatException ignored) {
			/*
				Invalid input that we ignore
			 */
		}

		return originalDataSets.size();
	}

	@Override
	public synchronized void shutdown() {
		for (final ThreadDetails webdriver : threadIdToCapMap.values()) {
			try {
				webdriver.getWebDriver().quit();
			} catch (final Exception ignored) {
				// do nothing and continue closing the other webdrivers
			}
		}

		/*
			Clear the map
		 */
		threadIdToCapMap.clear();

		/*
			Reset the list of available configurations
		 */
		currentCapability = 0;
		currentUrl = 0;
	}

	@Override
	public synchronized void shutdown(@NotNull final String name) {
		checkArgument(StringUtils.isNotBlank(name));

		if (threadIdToCapMap.containsKey(name)) {
			threadIdToCapMap.get(name).getWebDriver().quit();
			threadIdToCapMap.remove(name);
		}


	}
}
