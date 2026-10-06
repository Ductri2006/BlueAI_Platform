package com.blueai.ai.task;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties(prefix="app.ai.tasks")
public class AiTaskProperties {
    private Duration deadline=Duration.ofSeconds(25);
    public Duration getDeadline(){return deadline;}
    public void setDeadline(Duration value){if(value==null||value.compareTo(Duration.ofSeconds(10))<0||value.compareTo(Duration.ofSeconds(45))>0)throw new IllegalArgumentException("app.ai.tasks.deadline must be 10s..45s");deadline=value;}
}
