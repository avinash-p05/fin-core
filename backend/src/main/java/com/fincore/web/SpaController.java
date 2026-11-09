package com.fincore.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Client-side routing support for the React dashboard.
 *
 * <p>React Router handles URLs like {@code /payments/new} in the browser. When such a URL is loaded
 * directly (refresh, bookmark), the server must return {@code index.html} so the app can start and
 * render the route. Each path segment must contain no dot (so real files such as
 * {@code /assets/app.js} are served as files), and the first segment must not be a server area
 * ({@code api}, {@code actuator}, {@code v3}, {@code swagger-ui}), so unknown API URLs still return 404.
 */
@Controller
public class SpaController {

    /** First path segment: no dot, and not one of the server-owned areas. */
    private static final String FIRST = "{first:^(?!api$|actuator$|v3$|swagger-ui$|error$)[^.]+}";

    @GetMapping({
            "/" + FIRST,
            "/" + FIRST + "/{second:[^.]+}",
            "/" + FIRST + "/{second:[^.]+}/{third:[^.]+}",
    })
    public String forwardToIndex() {
        return "forward:/index.html";
    }
}
