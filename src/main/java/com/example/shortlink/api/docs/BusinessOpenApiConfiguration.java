package com.example.shortlink.api.docs;

import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.responses.*;
import io.swagger.v3.oas.models.security.*;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.*;

/** Completes generated DTOs and MVC routes with interceptor and implicit HEAD contracts. */
@Configuration(proxyBeanMethods = false)
public class BusinessOpenApiConfiguration {
    private static final String TOKEN = "InternalToken";
    private static final String RATE = "RATE_LIMIT_EXCEEDED：额度不足；Retry-After向上取整秒，仅提示等待，不保证下次获准。";
    private static final String UNAVAILABLE = "RATE_LIMIT_UNAVAILABLE：限流无法确认，业务尚未开始，无发号/写库/查询；不能解释为已保存。";
    private static final String STATS = "只查询已记录事件；best-effort异步可见，不保证所有访问入账。Asia/Shanghai含当天最近30统计日，省略日期默认最近7日；from/to必须同时提供且各一次，闭区间。范围UV整体去重，不能相加每日UV。HEAD无响应体且不计访问。";

    @Bean OpenApiCustomizer businessContracts() {
        return api -> {
            api.info(new Info().title("短链接业务 API").version("1")
                    .description("本地单实例演示。管理接口必须且只能提供一个X-Internal-Token，未配置管理秘密时404，缺失/错误/重复头401；鉴权先于限流和参数解析。令牌仅手动填写，不预填或持久保存。错误响应no-store。健康/指标属于独立管理端口，不在此业务API内。"));
            api.getComponents().addSecuritySchemes(TOKEN, new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                    .in(SecurityScheme.In.HEADER).name("X-Internal-Token")
                    .description("手动输入本地管理令牌；仅内存保留，关闭/刷新页面后重新输入。切勿使用真实生产秘密。"));
            schemaContracts(api);
            api.getPaths().forEach((path, item) -> {
                Operation operation = item.getPost() != null ? item.getPost() : item.getPut() != null ? item.getPut() : item.getGet();
                if (operation == null) return;
                boolean create = path.equals("/api/links");
                boolean redirect = path.equals("/s/{code}");
                boolean state = path.endsWith("/enabled");
                operation.setResponses(new ApiResponses());
                if (!create) operation.setParameters(new ArrayList<>(List.of(code())));
                if (!create && !redirect) {
                    operation.setSecurity(List.of(new SecurityRequirement().addList(TOKEN)));
                    response(operation, "401", "INTERNAL_UNAUTHORIZED：管理头缺失、错误或重复。", false);
                    response(operation, "404", "LINK_NOT_FOUND；未配置管理秘密时RESOURCE_NOT_FOUND。", false);
                }
                response(operation, "429", RATE, false).addHeaderObject("Retry-After", new Header()
                        .description("向上取整秒数；HEAD也返回此头。").schema(new IntegerSchema().minimum(java.math.BigDecimal.ONE)));
                response(operation, "500", create ? "SHORT_CODE_GENERATION_FAILED或INTERNAL_ERROR：不推断已提交，禁止盲重试。" : "INTERNAL_ERROR：无法完成业务；不泄露依赖异常。", false);
                if (create) {
                    operation.summary("匿名创建独立短链接映射").description("originalUrl为ASCII绝对HTTP/HTTPS URI，最多4096字符，无首尾空白；validMinutes省略/null为永久，整数1～5256000。连接对端IP创建桶默认容量3，每6秒补1；忽略伪造Forwarded。无请求幂等键，相同URL或POST重试可产生新的映射，不能以重试代替协调恢复。");
                    success(operation, "201", "MySQL已提交且缓存协调确认完成。", "CreateLinkResponse", false)
                            .addHeaderObject("Location", new Header().description("新短链接地址，与shortUrl相同。").schema(new StringSchema().format("uri")));
                    response(operation, "400", "INVALID_REQUEST：URL/有效时长/JSON不合法。", false);
                    coordination(operation, "CREATE_CACHE_COORDINATION_UNCONFIRMED", "CreateCacheCoordinationError");
                } else if (redirect) {
                    operation.summary("访问短链接并跳转").description("非法格式404且不访问Redis/MySQL；合法短码按连接对端IP跨短码GET/HEAD共用桶，默认60、每秒补10。Redis限流故障fail-open仍受每实例实际数据库加载max4限制。映射判断过期优先于禁用；正常GET决定跳转才best-effort交接访问事件，HEAD及拒绝无Cookie/事件/PV/UV。");
                    success(operation, "302", "跳转到原始URL，无响应体。", null, true)
                            .addHeaderObject("Location", new Header().description("原始URL。").schema(new StringSchema().format("uri")))
                            .addHeaderObject("Set-Cookie", new Header().description("仅正常GET且采集开启/可交接时可能返回匿名访客Cookie；HEAD及拒绝不返回。").schema(new StringSchema()));
                    response(operation, "404", "LINK_NOT_FOUND：短码非法或映射不存在。", false);
                    response(operation, "403", "LINK_DISABLED：未过期且禁用。", false);
                    response(operation, "410", "LINK_EXPIRED：已过期，优先于禁用。", false);
                    response(operation, "503", "REDIRECT_LOAD_BUSY：实际回源容量繁忙，立即拒绝，无新增等待队列，不缓存拒绝、不记访问。", false);
                } else if (state) {
                    operation.summary("内部设置启用状态").description("必须JSON布尔enabled。鉴权后固定共享管理写桶默认5、每秒补1；过期后禁止任何状态操作。重复目标409，不能恢复此前协调未确认；状态提交后最多3次同步尝试（等待50/100ms），耗尽报告已提交但协调未确认，无后台持续补偿。");
                    success(operation, "200", "本次提交和协调确认完成，不保证没有后续状态操作。", "EnabledStateResponse", false);
                    response(operation, "400", "INVALID_REQUEST：必须显式JSON布尔enabled。", false);
                    response(operation, "409", "LINK_ALREADY_ENABLED或LINK_ALREADY_DISABLED：拒绝重复目标。", false);
                    response(operation, "410", "LINK_EXPIRED：过期优先于重复状态，禁止变更。", false);
                    coordination(operation, "LINK_STATE_CACHE_COORDINATION_UNCONFIRMED", "StateCacheCoordinationError");
                } else {
                    operation.summary(path.endsWith("/stats") ? "查询PV/UV与每日趋势" : "查询已记录访问明细").description(STATS + "鉴权后两个接口GET/HEAD共用固定管理查询桶，默认5、每秒补1。停采仍可查询历史；identityVersions可能说明身份密钥轮换造成跨版本UV拆分。");
                    operation.addParametersItem(query("from", "YYYY-MM-DD；与to同时提供且各一次。", new StringSchema().format("date")));
                    operation.addParametersItem(query("to", "YYYY-MM-DD；不晚于今天，from≤to且均在30日窗口内。", new StringSchema().format("date")));
                    if (path.endsWith("/visits")) {
                        operation.addParametersItem(query("limit", "每次最多100项，默认20；只出现一次。", new IntegerSchema().minimum(java.math.BigDecimal.ONE).maximum(java.math.BigDecimal.valueOf(100))._default(20)));
                        operation.addParametersItem(query("cursor", "使用上次nextCursor，绑定短码和日期范围，不能修改或重复。", new StringSchema()));
                    }
                    success(operation, "200", STATS, path.endsWith("/stats") ? "VisitStatsResponse" : "VisitPageResponse", false);
                    response(operation, "400", "INVALID_REQUEST：日期/范围/limit/cursor不合法或重复。", false);
                    response(operation, "503", UNAVAILABLE + " STATS_BUSY：统计查询准入繁忙；STATS_QUERY_TIMEOUT：统计查询超时。均不代表已保存。", false);
                }
                if (item.getGet() != null) item.head(head(operation, redirect));
            });
        };
    }

    private static void schemaContracts(OpenAPI api) {
        Schema<?> create = api.getComponents().getSchemas().get("CreateLinkRequest");
        if (create != null) {
            create.addProperty("originalUrl", new StringSchema().maxLength(4096).description("ASCII绝对http/https URI，无首尾空白。").example("https://example.com/"));
            create.addProperty("validMinutes", new IntegerSchema().nullable(true).minimum(java.math.BigDecimal.ONE).maximum(java.math.BigDecimal.valueOf(5256000)).description("省略/null永久；必须JSON整数。"));
        }
        Schema<?> enabled = api.getComponents().getSchemas().get("SetEnabledRequest");
        if (enabled != null) enabled.addProperty("enabled", new BooleanSchema().description("必须显式JSON布尔，不能字符串/数字/null。")).required(List.of("enabled"));
        api.getComponents().addSchemas("ApiError", new ObjectSchema().addProperty("code", new StringSchema()).addProperty("message", new StringSchema()).required(List.of("code", "message")));
        for (String name : List.of("CreateCacheCoordinationError", "StateCacheCoordinationError"))
            api.getComponents().addSchemas(name, new ObjectSchema().addProperty("code", new StringSchema()).addProperty("message", new StringSchema()).addProperty("shortCode", new StringSchema()).required(List.of("code", "message", "shortCode")));
    }
    private static Parameter code() {
        return new Parameter().in("path").name("code").required(true).description("4～8位ASCII字母数字短码；非法格式404。").schema(new StringSchema().pattern("^[A-Za-z0-9]{4,8}$"));
    }
    private static Parameter query(String name, String description, Schema<?> schema) {
        return new Parameter().in("query").name(name).required(false).description(description).schema(schema);
    }
    private static ApiResponse response(Operation operation, String status, String description, boolean empty) {
        return success(operation, status, description, empty ? null : "ApiError", empty);
    }
    private static ApiResponse success(Operation operation, String status, String description, String schema, boolean empty) {
        var response = new ApiResponse().description(description);
        if (!empty && schema != null) response.content(new Content().addMediaType("application/json", new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + schema))));
        if (!status.equals("201")) response.addHeaderObject("Cache-Control", new Header().description("no-store").schema(new StringSchema()._enum(List.of("no-store"))));
        operation.getResponses().addApiResponse(status, response);
        return response;
    }
    private static void coordination(Operation operation, String code, String schema) {
        var response = response(operation, "503", UNAVAILABLE + " " + code + "：MySQL已提交，缓存协调未确认。保留shortCode，通过受控内部recoverCacheCoordination恢复，仅重试协调，不重新创建/重复PUT；该入口不是公开HTTP API。Redis更新可能已生效，不能从503推断回滚。", false);
        response.getContent().get("application/json").schema(new ComposedSchema().oneOf(List.of(new Schema<>().$ref("#/components/schemas/ApiError"), new Schema<>().$ref("#/components/schemas/" + schema))));
    }
    private static Operation head(Operation get, boolean redirect) {
        var head = new Operation().operationId(get.getOperationId() + "Head").summary(get.getSummary() + "（HEAD）")
                .description(get.getDescription() + " 所有HEAD响应无响应体；保留状态及适用响应头，不计访问。")
                .parameters(get.getParameters()).security(get.getSecurity()).responses(new ApiResponses());
        get.getResponses().forEach((status, original) -> {
            var response = new ApiResponse().description(original.getDescription());
            if (original.getHeaders() != null) {
                var headers = new LinkedHashMap<>(original.getHeaders());
                if (redirect) headers.remove("Set-Cookie");
                response.headers(headers);
            }
            head.getResponses().addApiResponse(status, response);
        });
        return head;
    }
}
