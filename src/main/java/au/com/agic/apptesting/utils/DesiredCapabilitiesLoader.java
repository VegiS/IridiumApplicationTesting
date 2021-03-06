package au.com.agic.apptesting.utils;


import org.openqa.selenium.remote.DesiredCapabilities;

import java.util.List;

import javax.validation.constraints.NotNull;

/**
 * Provides a service for loading desired capability profiles from configuration file <p> See
 * https://www.browserstack.com/automate/java for capabilities configurations
 */
public interface DesiredCapabilitiesLoader {

	/**
	 * @return A list of the Selenium capabilities loaded from the profile
	 */
	@NotNull
	List<DesiredCapabilities> getCapabilities();
}
