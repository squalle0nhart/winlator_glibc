package com.winlator.cmod.xserver.extensions;

import android.util.Log;
import static com.winlator.cmod.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import android.util.SparseArray;

import com.winlator.cmod.renderer.GPUImage;
import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.Bitmask;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.xserver.Pixmap;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.WindowManager;
import com.winlator.cmod.xserver.XClient;
import com.winlator.cmod.xserver.XLock;
import com.winlator.cmod.xserver.XResource;
import com.winlator.cmod.xserver.XResourceManager;
import com.winlator.cmod.xserver.XServer;
import com.winlator.cmod.xserver.errors.BadImplementation;
import com.winlator.cmod.xserver.errors.BadMatch;
import com.winlator.cmod.xserver.errors.BadPixmap;
import com.winlator.cmod.xserver.errors.BadWindow;
import com.winlator.cmod.xserver.errors.XRequestError;
import com.winlator.cmod.xserver.events.PresentCompleteNotify;
import com.winlator.cmod.xserver.events.PresentConfigureNotify;
import com.winlator.cmod.xserver.events.PresentIdleNotify;

import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public class PresentExtension implements Extension, XResourceManager.OnResourceLifecycleListener, WindowManager.OnWindowModificationListener {
    public static final byte MAJOR_OPCODE = -103;
    private static final int FAKE_INTERVAL = 1000000 / 60;
    public enum Kind {PIXMAP, MSC_NOTIFY}
    public enum Mode {COPY, FLIP, SKIP}
    private final SparseArray<Event> events = new SparseArray<>();
    private SyncExtension syncExtension;
    private XServer xServer;
    private final Object limiterLock = new Object();
    private HashMap<Integer, Long> limiterDeadlines;
    private ScheduledThreadPoolExecutor limiterExecutor;

    private static abstract class ClientOpcodes {
        private static final byte QUERY_VERSION = 0;
        private static final byte PRESENT_PIXMAP = 1;
        private static final byte SELECT_INPUT = 3;
    }

    private static class Event {
        private Window window;
        private XClient client;
        private int id;
        private Bitmask mask;
    }
    
    public PresentExtension(XServer xserver) {
        this.xServer = xserver;
        this.xServer.windowManager.addOnResourceLifecycleListener(this);
        this.xServer.windowManager.addOnWindowModificationListener(this);
    }

    @Override
    public String getName() {
        return "Present";
    }

    @Override
    public byte getMajorOpcode() {
        return MAJOR_OPCODE;
    }

    @Override
    public byte getFirstErrorId() {
        return 0;
    }

    @Override
    public byte getFirstEventId() {
        return 0;
    }

    private void sendConfigureNotify(Window window, int pixmapFlags) {
        synchronized (events) {
            for (int i = 0; i < events.size(); i++) {
                Event event = events.valueAt(i);
                if (event.window == window && event.mask.isSet(PresentConfigureNotify.getEventMask())) {
                    event.client.sendEvent(new PresentConfigureNotify(event.id, window, pixmapFlags));
                }
            }
        }
    }

    private void sendIdleNotify(Window window, Pixmap pixmap, int serial, int idleFence) {
        if (idleFence != 0) syncExtension.setTriggered(idleFence);

        synchronized (events) {
            for (int i = 0; i < events.size(); i++) {
                Event event = events.valueAt(i);
                if (event.window == window && event.mask.isSet(PresentIdleNotify.getEventMask())) {
                    event.client.sendEvent(new PresentIdleNotify(event.id, window, pixmap, serial, idleFence));
                }
            }
        }
    }

    private void sendCompleteNotify(Window window, int serial, Kind kind, Mode mode, long ust, long msc) {
        synchronized (events) {
            for (int i = 0; i < events.size(); i++) {
                Event event = events.valueAt(i);
                if (event.window == window && event.mask.isSet(PresentCompleteNotify.getEventMask())) {
                    event.client.sendEvent(new PresentCompleteNotify(event.id, window, serial, kind, mode, ust, msc));
                }
            }
        }
    }

    private void scheduleIdleNotify(Window window, Pixmap pixmap, int serial,
                                    int idleFence, int targetFps) {
        final long frameDurationNs = 1_000_000_000L / targetFps;
        final long now = System.nanoTime();
        final long deadline;
        final ScheduledThreadPoolExecutor executor;

        synchronized (limiterLock) {
            if (limiterDeadlines == null) limiterDeadlines = new HashMap<>();
            Long previous = limiterDeadlines.get(window.id);
            deadline = previous == null || previous < now
                    ? now + frameDurationNs : previous + frameDurationNs;
            limiterDeadlines.put(window.id, deadline);

            if (limiterExecutor == null) {
                ThreadFactory factory = runnable -> {
                    Thread thread = new Thread(runnable, "PresentFpsLimiter");
                    thread.setDaemon(true);
                    return thread;
                };
                limiterExecutor = new ScheduledThreadPoolExecutor(1, factory);
                limiterExecutor.setRemoveOnCancelPolicy(true);
            }
            executor = limiterExecutor;
        }

        executor.schedule(() -> sendIdleNotify(window, pixmap, serial, idleFence),
                Math.max(0L, deadline - now), TimeUnit.NANOSECONDS);
    }

    private static void queryVersion(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        inputStream.skip(8);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(1);
            outputStream.writeInt(2);
            outputStream.writePad(16);
        }
    }

    private void presentPixmap(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int windowId = inputStream.readInt();
        int pixmapId = inputStream.readInt();
        int serial = inputStream.readInt();
        inputStream.skip(8);
        short xOff = inputStream.readShort();
        short yOff = inputStream.readShort();
        inputStream.skip(8);
        int idleFence = inputStream.readInt();
        inputStream.skip(client.getRemainingRequestLength());

        final Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);

        final Pixmap pixmap = client.xServer.pixmapManager.getPixmap(pixmapId);
        if (pixmap == null) throw new BadPixmap(pixmapId);

        int targetFps = client.xServer.getXServerView() != null
                ? client.xServer.getXServerView().getFpsLimit() : 0;
        if (targetFps > 1000) targetFps = 1000;

        if (targetFps <= 0) {
            long ust = System.nanoTime() / 1000;
            long msc = ust / FAKE_INTERVAL;

            pixmap.drawable.updateDirect();
            sendIdleNotify(window, pixmap, serial, idleFence);
            sendCompleteNotify(window, serial, Kind.PIXMAP, Mode.COPY, ust, msc);
            return;
        }

        long ust = System.nanoTime() / 1000;
        long msc = ust / (1_000_000L / targetFps);

        pixmap.drawable.updateDirect();
        sendCompleteNotify(window, serial, Kind.PIXMAP, Mode.COPY, ust, msc);
        scheduleIdleNotify(window, pixmap, serial, idleFence, targetFps);
    }

    private void selectInput(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int eventId = inputStream.readInt();
        int windowId = inputStream.readInt();
        Bitmask mask = new Bitmask(inputStream.readInt());

        Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);

        synchronized (events) {
            Event event = events.get(eventId);
            if (event != null) {
                if (event.window != window || event.client != client) throw new BadMatch();

                if (!mask.isEmpty()) {
                    event.mask = mask;
                }
                else events.remove(eventId);
            }
            else {
                event = new Event();
                event.id = eventId;
                event.window = window;
                event.client = client;
                event.mask = mask;
                events.put(eventId, event);
            }
        }
    }
    
    
    @Override
    public void onUpdateWindowGeometry(Window window, boolean resized) {
        sendConfigureNotify(window, 0);
    }

    @Override
    public void onFreeResource(XResource resource) {
        if (resource instanceof Window) {
            Window window = (Window) resource;
            synchronized (events) {
                for (int i = events.size() - 1; i >= 0; i--) {
                    Event event = events.valueAt(i);
                    if (event.window == window) {
                        if (event.mask.isSet(PresentConfigureNotify.getEventMask())) {
                            event.client.sendEvent(new PresentConfigureNotify(
                                    event.id,
                                    window,
                                    PresentConfigureNotify.PIXMAP_FLAG_WINDOW_DESTROYED));
                        }
                        events.removeAt(i);
                    }
                }
            }
        }
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int opcode = client.getRequestData();
        if (syncExtension == null) syncExtension = client.xServer.getExtension(SyncExtension.MAJOR_OPCODE);

        switch (opcode) {
            case ClientOpcodes.QUERY_VERSION :
                queryVersion(client, inputStream, outputStream);
                break;
            case ClientOpcodes.PRESENT_PIXMAP:
                try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.PIXMAP_MANAGER)) {
                    presentPixmap(client, inputStream, outputStream);
                }
                break;
            case ClientOpcodes.SELECT_INPUT:
                try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                    selectInput(client, inputStream, outputStream);
                }
                break;
            default:
                throw new BadImplementation();
        }
    }
}
