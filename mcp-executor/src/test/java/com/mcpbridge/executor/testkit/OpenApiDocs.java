package com.mcpbridge.executor.testkit;

/**
 * 测试 REST 服务对外暴露的接口文档（OpenAPI 3.0 JSON）。
 *
 * <p>供平台「注册解析」使用：把文档 URL 填进注册页（或直接粘贴本文），
 * mcp-manager 即可解析出 tool 集合。文档里的每个 path 都与
 * {@link TestRestService} 的真实实现严格一一对应——如果两端漂移，
 * 要么注册解析出的 tool 调不通（HTTP 404），要么冒烟测试失败，不会悄悄错。
 *
 * <p>两个业务域（order / user）刻意分开成两份文档：
 * 同一 MCP Server 下注册两次，即可验证「一个 Server 聚合多个 REST 服务、
 * 同名 tool 自动加服务前缀去重」的多上游语义。
 *
 * <p>servers 声明了默认端口 18080（与 {@code main} 常驻模式一致），
 * 手工验收时可直接注册；自动化测试里 baseUrls 由测试自行指定随机端口，不依赖此值。
 */
public final class OpenApiDocs {

    /** order 域文档路径前缀。 */
    public static final String ORDER_PATH_PREFIX = "/api/orders";

    /** user 域文档路径前缀。 */
    public static final String USER_PATH_PREFIX = "/api/users";

    private OpenApiDocs() {
    }

    /** order 服务 OpenAPI 3.0 文档。 */
    public static String order() {
        return """
                {
                  "openapi": "3.0.1",
                  "info": {
                    "title": "订单服务",
                    "description": "MCP 桥接平台测试用 REST 服务：订单域",
                    "version": "1.0.0"
                  },
                  "servers": [
                    { "url": "http://127.0.0.1:18080" }
                  ],
                  "paths": {
                    "/api/orders": {
                      "get": {
                        "operationId": "listOrders",
                        "summary": "订单列表",
                        "parameters": [
                          { "name": "status", "in": "query", "required": false,
                            "schema": { "type": "string", "enum": ["CREATED", "PAID", "SHIPPED", "CANCELLED"] } },
                          { "name": "page", "in": "query", "required": false,
                            "schema": { "type": "integer", "format": "int32", "default": 1 } },
                          { "name": "size", "in": "query", "required": false,
                            "schema": { "type": "integer", "format": "int32", "default": 20 } }
                        ],
                        "responses": {
                          "200": { "description": "分页订单列表",
                            "content": { "application/json": {
                              "schema": { "$ref": "#/components/schemas/OrderPage" } } } }
                        }
                      },
                      "post": {
                        "operationId": "createOrder",
                        "summary": "创建订单",
                        "requestBody": { "required": true,
                          "content": { "application/json": {
                            "schema": { "$ref": "#/components/schemas/CreateOrderRequest" } } } },
                        "responses": {
                          "201": { "description": "创建成功",
                            "content": { "application/json": {
                              "schema": { "$ref": "#/components/schemas/Order" } } } },
                          "400": { "description": "请求体不合法" }
                        }
                      }
                    },
                    "/api/orders/{orderId}": {
                      "get": {
                        "operationId": "getOrder",
                        "summary": "订单详情",
                        "parameters": [
                          { "name": "orderId", "in": "path", "required": true,
                            "schema": { "type": "integer", "format": "int64" } }
                        ],
                        "responses": {
                          "200": { "description": "订单对象",
                            "content": { "application/json": {
                              "schema": { "$ref": "#/components/schemas/Order" } } } },
                          "404": { "description": "订单不存在" }
                        }
                      },
                      "put": {
                        "operationId": "updateOrder",
                        "summary": "更新订单状态",
                        "parameters": [
                          { "name": "orderId", "in": "path", "required": true,
                            "schema": { "type": "integer", "format": "int64" } }
                        ],
                        "requestBody": { "required": true,
                          "content": { "application/json": {
                            "schema": { "$ref": "#/components/schemas/UpdateOrderRequest" } } } },
                        "responses": {
                          "200": { "description": "更新成功",
                            "content": { "application/json": {
                              "schema": { "$ref": "#/components/schemas/Order" } } } },
                          "404": { "description": "订单不存在" }
                        }
                      },
                      "delete": {
                        "operationId": "deleteOrder",
                        "summary": "删除订单",
                        "parameters": [
                          { "name": "orderId", "in": "path", "required": true,
                            "schema": { "type": "integer", "format": "int64" } }
                        ],
                        "responses": {
                          "204": { "description": "删除成功" },
                          "404": { "description": "订单不存在" }
                        }
                      }
                    }
                  },
                  "components": {
                    "schemas": {
                      "Order": {
                        "type": "object",
                        "properties": {
                          "id": { "type": "integer", "format": "int64" },
                          "customerId": { "type": "integer", "format": "int64" },
                          "amount": { "type": "number", "format": "double" },
                          "status": { "type": "string", "enum": ["CREATED", "PAID", "SHIPPED", "CANCELLED"] },
                          "createdAt": { "type": "string", "format": "date-time" }
                        }
                      },
                      "OrderPage": {
                        "type": "object",
                        "properties": {
                          "items": { "type": "array", "items": { "$ref": "#/components/schemas/Order" } },
                          "total": { "type": "integer", "format": "int64" },
                          "page": { "type": "integer", "format": "int32" },
                          "size": { "type": "integer", "format": "int32" }
                        }
                      },
                      "CreateOrderRequest": {
                        "type": "object",
                        "required": ["customerId", "amount"],
                        "properties": {
                          "customerId": { "type": "integer", "format": "int64" },
                          "amount": { "type": "number", "format": "double" }
                        }
                      },
                      "UpdateOrderRequest": {
                        "type": "object",
                        "required": ["status"],
                        "properties": {
                          "status": { "type": "string", "enum": ["CREATED", "PAID", "SHIPPED", "CANCELLED"] }
                        }
                      }
                    }
                  }
                }
                """;
    }

    /** user 服务 OpenAPI 3.0 文档。 */
    public static String user() {
        return """
                {
                  "openapi": "3.0.1",
                  "info": {
                    "title": "用户服务",
                    "description": "MCP 桥接平台测试用 REST 服务：用户域",
                    "version": "1.0.0"
                  },
                  "servers": [
                    { "url": "http://127.0.0.1:18080" }
                  ],
                  "paths": {
                    "/api/users": {
                      "post": {
                        "operationId": "createUser",
                        "summary": "创建用户",
                        "requestBody": { "required": true,
                          "content": { "application/json": {
                            "schema": { "$ref": "#/components/schemas/CreateUserRequest" } } } },
                        "responses": {
                          "201": { "description": "创建成功",
                            "content": { "application/json": {
                              "schema": { "$ref": "#/components/schemas/User" } } } },
                          "400": { "description": "请求体不合法" }
                        }
                      }
                    },
                    "/api/users/{userId}": {
                      "get": {
                        "operationId": "getUser",
                        "summary": "用户详情",
                        "parameters": [
                          { "name": "userId", "in": "path", "required": true,
                            "schema": { "type": "integer", "format": "int64" } }
                        ],
                        "responses": {
                          "200": { "description": "用户对象",
                            "content": { "application/json": {
                              "schema": { "$ref": "#/components/schemas/User" } } } },
                          "404": { "description": "用户不存在" }
                        }
                      }
                    }
                  },
                  "components": {
                    "schemas": {
                      "User": {
                        "type": "object",
                        "properties": {
                          "id": { "type": "integer", "format": "int64" },
                          "name": { "type": "string" },
                          "email": { "type": "string", "format": "email" },
                          "createdAt": { "type": "string", "format": "date-time" }
                        }
                      },
                      "CreateUserRequest": {
                        "type": "object",
                        "required": ["name", "email"],
                        "properties": {
                          "name": { "type": "string" },
                          "email": { "type": "string", "format": "email" }
                        }
                      }
                    }
                  }
                }
                """;
    }
}
