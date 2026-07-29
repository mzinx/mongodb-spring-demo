package com.mzinx.demo.web;

import java.util.Set;
import java.util.TreeSet;

import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes the live instance registry maintained by mongodb-spring-discovery
 * (heartbeats in the {@code _instances} collection, propagated through a
 * change stream into the shared {@code instances} bean).
 */
@RestController
@RequestMapping("/api/instances")
public class InstanceController {

    private final Set<String> instances;

    @SuppressWarnings("unchecked")
    InstanceController(ApplicationContext context) {
        // Resolved by bean name: injecting Set<String> by type would collect
        // all String beans instead of the "instances" bean.
        this.instances = (Set<String>) context.getBean("instances", Set.class);
    }

    @GetMapping
    public Set<String> list() {
        return new TreeSet<>(instances);
    }
}
