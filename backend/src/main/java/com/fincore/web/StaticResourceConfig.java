package com.fincore.web;

import java.time.Duration;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Cache rules for the dashboard files (previously nginx's job):
 * <ul>
 *   <li>{@code /assets/**}: Vite puts a content hash in every file name, so a changed file gets a new
 *       URL. These can be cached for a year and never revalidated.</li>
 *   <li>Everything else, especially {@code index.html}: {@code no-cache}, so browsers always check for
 *       a new release and pick up the new asset URLs.</li>
 * </ul>
 */
@Configuration
public class StaticResourceConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noCache());
    }
}
