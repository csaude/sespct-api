package mz.org.csaude.sespcet.api.util;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Calendar;

@Getter
@Setter
@NoArgsConstructor
public class SubmitedSessions {

    private String district;

    private String programmaticArea;

    private Long totalSubmited;

    private Calendar lastUpdate;

    public SubmitedSessions(final String district, final String programmaticArea, final Long totalSubmited,
                            final Calendar lastUpdate) {
        this.district = district;
        this.programmaticArea = programmaticArea;
        this.totalSubmited = totalSubmited;
        this.lastUpdate = lastUpdate;
    }
}
