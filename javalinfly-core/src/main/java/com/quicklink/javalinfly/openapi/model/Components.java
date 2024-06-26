package com.quicklink.javalinfly.openapi.model;

import java.util.Map;

public class Components {
  public final SecuritySchemes securitySchemes;
  public final Map<String, Schema> schemas;

  public Components(SecuritySchemes securitySchemes, Map<String, Schema> schemas) {
    this.securitySchemes = securitySchemes;
    this.schemas = schemas;
  }

  public static class SecuritySchemes {
    public final BearerAuth bearerAuth;

    public SecuritySchemes(BearerAuth bearerAuth) {
      this.bearerAuth = bearerAuth;
    }

    public static class BearerAuth {
      public String type;
      public String scheme;
      public String bearerFormat;


      public BearerAuth(String type, String scheme, String bearerFormat) {
        this.type = type;
        this.scheme = scheme;
        this.bearerFormat = bearerFormat;
      }
    }
  }

}
