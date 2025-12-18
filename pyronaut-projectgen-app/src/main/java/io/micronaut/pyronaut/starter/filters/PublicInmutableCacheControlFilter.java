package io.micronaut.pyronaut.starter.filters;

import io.micronaut.http.HttpHeaders;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.ResponseFilter;
import io.micronaut.http.annotation.ServerFilter;
import io.micronaut.http.cachecontrol.CacheControl;
import io.micronaut.http.filter.FilterPatternStyle;

import java.time.Duration;

@ServerFilter(patternStyle = FilterPatternStyle.REGEX,
        value = "/assets/(bootstrap|images|prism|htmx|treeview)/.*")
class PublicInmutableCacheControlFilter {
    @ResponseFilter
    void filterResponse(MutableHttpResponse<?> rsp) {
        if (!rsp.getHeaders().contains(HttpHeaders.CACHE_CONTROL)) {
            rsp.cacheControl(
                    CacheControl.builder()
                            .publicDirective()
                            .maxAge(Duration.ofDays(365))
                            .inmutable()
                            .build());
        }
    }
}
