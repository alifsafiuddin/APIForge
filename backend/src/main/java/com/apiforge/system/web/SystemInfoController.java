package com.apiforge.system.web;

import java.time.Instant;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.apiforge.system.service.SystemInfoService;
import com.apiforge.system.service.SystemInfoService.SystemInfo;

@RestController
@RequestMapping("/api/v1/system")
public class SystemInfoController {

    private final SystemInfoService systemInfoService;

    public SystemInfoController(SystemInfoService systemInfoService) {
        this.systemInfoService = systemInfoService;
    }

    @GetMapping("/info")
    public SystemInfoResponse info() {
        return SystemInfoResponse.from(systemInfoService.describe());
    }

    public record SystemInfoResponse(String name, String version, String apiVersion, Instant serverTime) {

        static SystemInfoResponse from(SystemInfo info) {
            return new SystemInfoResponse(info.name(), info.version(), info.apiVersion(), info.serverTime());
        }
    }
}
