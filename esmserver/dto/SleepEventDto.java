package io.github.rladmstj.esmserver.dto;

import java.sql.Timestamp;
import io.github.rladmstj.esmserver.model.SleepEvent;

public class SleepEventDto {
    public Long dataId;
    public Integer sensorId;
    public Timestamp sensorTime;
    public Timestamp sensorTimeEnd;
    public Integer hr;
    public Integer rr;
    public Boolean snoring;
    public Integer sdnn1;
    public Integer rmssd;
    public Integer mvtScore;
    public Integer chestMovementRate;

    public static SleepEventDto fromEntity(SleepEvent e) {
        SleepEventDto d = new SleepEventDto();
        d.dataId = e.getDataId();
        d.sensorId = e.getSensorId();
        d.sensorTime = e.getSensorTime();
        d.sensorTimeEnd = e.getSensorTimeEnd();
        d.hr = e.getHr();
        d.rr = e.getRr();
        d.snoring = e.getSnoring();
        d.sdnn1 = e.getSdnn1();
        d.rmssd = e.getRmssd();
        d.mvtScore = e.getMvtScore();
        d.chestMovementRate = e.getChestMovementRate();
        return d;
    }
}
