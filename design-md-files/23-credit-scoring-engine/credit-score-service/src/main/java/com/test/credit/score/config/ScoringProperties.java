package com.test.credit.score.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.scoring")
public class ScoringProperties {

    private int challengerTrafficPct = 10;
    private int scoreCacheTtlMinutes = 5;
    private int idempotencyTtlHours = 24;

    public int getChallengerTrafficPct() { return challengerTrafficPct; }
    public void setChallengerTrafficPct(int v) { this.challengerTrafficPct = v; }

    public int getScoreCacheTtlMinutes() { return scoreCacheTtlMinutes; }
    public void setScoreCacheTtlMinutes(int v) { this.scoreCacheTtlMinutes = v; }

    public int getIdempotencyTtlHours() { return idempotencyTtlHours; }
    public void setIdempotencyTtlHours(int v) { this.idempotencyTtlHours = v; }
}
