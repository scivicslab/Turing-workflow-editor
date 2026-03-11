package com.scivicslab.workfloweditor.rest;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

/**
 * CORS filter for external API access (coder-agent, etc.).
 */
@Provider
public class CorsFilter implements ContainerResponseFilter {

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        response.getHeaders().putSingle("Access-Control-Allow-Origin", "*");
        response.getHeaders().putSingle("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        response.getHeaders().putSingle("Access-Control-Allow-Headers", "Content-Type, Accept");
        response.getHeaders().putSingle("Access-Control-Max-Age", "86400");
    }
}
