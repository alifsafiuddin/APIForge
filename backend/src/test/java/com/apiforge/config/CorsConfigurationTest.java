package com.apiforge.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import com.apiforge.common.error.ProblemResponseWriter;
import com.apiforge.support.SliceCredentialEpochs;
import com.apiforge.system.service.SystemInfoService;
import com.apiforge.system.web.SystemInfoController;

import org.springframework.context.annotation.Import;

@WebMvcTest(controllers = SystemInfoController.class, properties = "apiforge.cors.allowed-origins=http://localhost:5173")
@Import({ SystemInfoService.class, ClockConfig.class, SecurityConfig.class, ProblemResponseWriter.class,
        SliceCredentialEpochs.class })
class CorsConfigurationTest {

    private static final String ALLOWED = "http://localhost:5173";

    @Autowired
    MockMvc mockMvc;

    @Test
    void preflightFromConfiguredOriginIsAllowed() throws Exception {
        mockMvc.perform(options("/api/v1/system/info")
                        .header(HttpHeaders.ORIGIN, ALLOWED)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "X-Request-Id"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    @Test
    void simpleRequestFromConfiguredOriginExposesRequestId() throws Exception {
        mockMvc.perform(get("/api/v1/system/info").header(HttpHeaders.ORIGIN, ALLOWED))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, "X-Request-Id"));
    }

    @Test
    void preflightFromOtherOriginIsRejected() throws Exception {
        mockMvc.perform(options("/api/v1/system/info")
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void preflightWithUnlistedHeaderIsRejected() throws Exception {
        mockMvc.perform(options("/api/v1/system/info")
                        .header(HttpHeaders.ORIGIN, ALLOWED)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "X-Not-Allowed"))
                .andExpect(status().isForbidden());
    }
}
