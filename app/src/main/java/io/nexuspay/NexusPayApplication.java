package io.nexuspay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.modulith.Modulithic;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * NexusPay application entry point.
 *
 * <p>Lives at the {@code io.nexuspay} root (not {@code io.nexuspay.app}) so
 * that Spring Boot's auto-configuration packages — which drive JPA
 * entity/repository scanning — and Spring Modulith's module detection cover
 * all {@code io.nexuspay.*} module packages. With the class nested under
 * {@code io.nexuspay.app}, sibling modules' entities and repositories are
 * invisible at runtime and Modulith sees no modules at all.</p>
 */
@SpringBootApplication
@Modulithic(
        systemName = "NexusPay",
        sharedModules = {"common"}
)
@EnableScheduling
// considerNestedRepositories=true: four out-adapters (Invoice, Billing, Dispute,
// Reconciliation) declare their Spring Data interfaces as nested types — the
// default scan ignores those, leaving the adapters' constructor deps unsatisfied
// (NoSuchBeanDefinitionException). Base package defaults to io.nexuspay (this
// class's package), matching the prior auto-configured scan scope.
@EnableJpaRepositories(considerNestedRepositories = true)
public class NexusPayApplication {

    public static void main(String[] args) {
        SpringApplication.run(NexusPayApplication.class, args);
    }
}
