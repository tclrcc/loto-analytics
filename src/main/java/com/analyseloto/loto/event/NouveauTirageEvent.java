package com.analyseloto.loto.event;

import com.analyseloto.loto.entity.LotoTirage;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class NouveauTirageEvent extends ApplicationEvent {
    private final LotoTirage tirage;

    public NouveauTirageEvent(Object source, LotoTirage tirage) {
        super(source);
        this.tirage = tirage;
    }
}
