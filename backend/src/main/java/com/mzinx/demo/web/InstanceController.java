package com.mzinx.demo.web;

import java.util.Set;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mzinx.mongodb.changestream.InstanceRegistry;

/**
 * Exposes the live instance registry maintained by mongodb-spring-discovery
 * (heartbeats in the {@code _instances} collection, propagated through a
 * change stream into the shared {@link InstanceRegistry} bean).
 */
@RestController
@RequestMapping("/api/instances")
public class InstanceController {

    private final InstanceRegistry instanceRegistry;

    InstanceController(InstanceRegistry instanceRegistry) {
        this.instanceRegistry = instanceRegistry;
    }

    @GetMapping
    public Set<String> list() {
        return instanceRegistry.all();
    }
}
