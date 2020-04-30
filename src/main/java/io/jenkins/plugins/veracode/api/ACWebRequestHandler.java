package io.jenkins.plugins.veracode.api;

import com.veracode.http.WebRequestHandlerImpl;

import io.jenkins.plugins.veracode.utils.UserAgentUtil;

/**
 * The ACWebRequestHandler class enables the Veracode Java API wrapper to make
 * web requests to Veracode with a custom User-Agent header value.
 *
 * This class is not intended to be called from user code.
 *
 *
 */
public final class ACWebRequestHandler extends WebRequestHandlerImpl {

    @Override
    public String getDefaultUserAgentHeaderValue() {
        return UserAgentUtil.getVersionDetails();
    }

}