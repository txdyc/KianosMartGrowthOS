package com.kiano.content.workflow;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * kiano.content.workflows.* configuration. Defaults mirror application.yml so
 * that plain unit tests can construct the properties without a Spring context.
 */
@Component
@ConfigurationProperties(prefix = "kiano.content.workflows")
public class WorkflowProperties {

    /** Model licenses that permit commercial use; anything else is rejected. */
    private Set<String> allowedLicenses = new LinkedHashSet<>(List.of(
            "MIT", "Apache-2.0", "BSD-2-Clause", "BSD-3-Clause", "CreativeML-OpenRAIL++-M"));

    public Set<String> getAllowedLicenses() {
        return allowedLicenses;
    }

    public void setAllowedLicenses(Set<String> allowedLicenses) {
        this.allowedLicenses = allowedLicenses;
    }
}
