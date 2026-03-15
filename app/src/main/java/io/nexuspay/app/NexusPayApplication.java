package io.nexuspay.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "io.nexuspay")
@Modulithic(
        systemName = "NexusPay",
        sharedModules = {"common"}
)
@EnableScheduling
public class NexusPayApplication {

    public static void main(String[] args) {
        SpringApplication.run(NexusPayApplication.class, args);
    }
}
