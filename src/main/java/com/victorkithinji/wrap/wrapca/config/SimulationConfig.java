package com.victorkithinji.wrap.wrapca.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "wrap.simulation")
@Data
public class SimulationConfig {

    private double cellSizeMetres;
    private int timeStepMinutes;
    private int monteCarloRuns;
    private int threadPoolSize;
    private int phase1HorizonHours;
}