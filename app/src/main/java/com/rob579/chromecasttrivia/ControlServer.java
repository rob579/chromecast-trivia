package com.rob579.chromecasttrivia;

import java.util.HashMap;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * Web server running on the Chromecast itself. A phone on the same Wi-Fi
 * opens it to set up rounds, pick the current round and type answers.
 */
class ControlServer extends NanoHTTPD {

    private final MainActivity app;

    ControlServer(MainActivity app, int port) {
        super(port);
        this.app = app;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        boolean post = session.getMethod() == Method.POST;
        try {
            Map<String, String> body = new HashMap<>();
            if (post) {
                session.parseBody(body);
            }

            if (uri.equals("/") || uri.equals("/index.html")) {
                return noCache(newFixedLengthResponse(Response.Status.OK,
                        "text/html; charset=utf-8", app.readAsset("control.html")));
            }
            if (uri.equals("/api/state")) {
                if (post) {
                    String json = body.get("postData");
                    if (json == null) {
                        return error(Response.Status.BAD_REQUEST, "Empty request");
                    }
                    app.setState(json);
                }
                return noCache(newFixedLengthResponse(Response.Status.OK,
                        "application/json; charset=utf-8", app.getState()));
            }
            if (uri.equals("/api/cmd") && post) {
                Map<String, String> params = session.getParms();
                String name = params.get("c");
                int seconds = 0;
                try {
                    seconds = Integer.parseInt(params.get("n"));
                } catch (Exception ignored) {
                }
                seconds = Math.max(-600, Math.min(600, seconds));
                app.runCommand(name == null ? "" : name, seconds);
                return noCache(newFixedLengthResponse(Response.Status.OK, "text/plain", "ok"));
            }
            return error(Response.Status.NOT_FOUND, "Not found");
        } catch (Exception e) {
            return error(Response.Status.INTERNAL_ERROR, String.valueOf(e.getMessage()));
        }
    }

    private static Response noCache(Response r) {
        r.addHeader("Cache-Control", "no-store");
        return r;
    }

    private static Response error(Response.Status status, String message) {
        return newFixedLengthResponse(status, "text/plain; charset=utf-8", message);
    }
}
