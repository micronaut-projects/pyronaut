package io.micronaut.pyronaut.starter.controllers;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.views.View;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Controller
class HomeController {
    @View("index.html")
    @Get
    @Produces(MediaType.TEXT_HTML)
    Map<String, Object> index(@Nullable @QueryValue List<String> features) {
        return Collections.singletonMap("features", features);
    }
}
