package com.redhat.rhjmc.containerjfr.core;

import java.util.List;
import java.util.Map;

import com.redhat.rhjmc.containerjfr.core.net.JFRConnection;

import org.openjdk.jmc.common.unit.IOptionDescriptor;
import org.openjdk.jmc.rjmx.services.jfr.IEventTypeInfo;
import org.openjdk.jmc.rjmx.services.jfr.IRecordingDescriptor;

public class JFRService {

    private final JFRConnection connection;

    public JFRService(JFRConnection connection) {
        this.connection = connection;
    }

    public IRecordingDescriptor startRecording(RecordingOptionsCustomizer recordingOptionsCustomizer, EventOptionsCustomizer eventOptionsCustomizer) throws FlightRecorderException {
        try {
            return connection
                .getService()
                .start(recordingOptionsCustomizer.asMap(), eventOptionsCustomizer.asMap());
        } catch (org.openjdk.jmc.rjmx.services.jfr.FlightRecorderException e) {
            throw new FlightRecorderException(e);
        }
    }

    public void stopRecording(IRecordingDescriptor recording) throws FlightRecorderException {
        try {
            connection.getService().stop(recording);
        } catch (org.openjdk.jmc.rjmx.services.jfr.FlightRecorderException e) {
            throw new FlightRecorderException(e);
        }
    }

    public List<IRecordingDescriptor> getAvailableRecordings() throws FlightRecorderException {
        try {
            return connection.getService().getAvailableRecordings();
        } catch (org.openjdk.jmc.rjmx.services.jfr.FlightRecorderException e) {
            throw new FlightRecorderException(e);
        }
    }

    public List<? extends IEventTypeInfo> getAvailableEventTypes() throws FlightRecorderException {
        try {
            return capture(connection.getService().getAvailableEventTypes());
        } catch (org.openjdk.jmc.rjmx.services.jfr.FlightRecorderException e) {
            throw new FlightRecorderException(e);
        }
    }

    public Map<String, IOptionDescriptor<?>> getAvailableRecordingOptions() throws FlightRecorderException {
        try {
            return connection.getService().getAvailableRecordingOptions();
        } catch (org.openjdk.jmc.rjmx.services.jfr.FlightRecorderException e) {
            throw new FlightRecorderException(e);
        }
    }

    static <T, V> V capture(T t) {
        // TODO clean up this generics hack
        return (V) t;
    }

}
