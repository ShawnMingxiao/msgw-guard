package com.msgwguard.ibmi;

import com.msgwguard.domain.Settings;
import org.springframework.stereotype.Component;

@Component
public class ClientFactory {

    private final SimulatorClient simulator;
    private volatile IbmIClient cachedJt400;
    private volatile String cachedKey;

    public ClientFactory(SimulatorClient simulator) {
        this.simulator = simulator;
    }

    public IbmIClient get(Settings settings) {
        String adapter = settings.adapter == null ? "simulator" : settings.adapter;
        if ("simulator".equalsIgnoreCase(adapter)) {
            return simulator;
        }
        String key = n(settings.ibmiHost) + "|" + n(settings.ibmiUser) + "|"
                + n(settings.ibmiPassword) + "|" + n(settings.ibmiSubsystem) + "|"
                + n(settings.messageQueuePathTemplate);
        synchronized (this) {
            if (cachedJt400 == null || !key.equals(cachedKey)) {
                cachedJt400 = new Jt400Client(settings);
                cachedKey = key;
            }
        }
        return cachedJt400;
    }

    public void invalidate() {
        synchronized (this) {
            cachedJt400 = null;
            cachedKey = null;
        }
    }

    private String n(String value) {
        return value == null ? "" : value;
    }
}
