package com.hmdp.config;

import com.hmdp.dto.Result;
import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WebErrorContractTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        UserHolder.removeUser();

        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new ContractProbeController())
                .setControllerAdvice(new WebExceptionAdvice())
                .addInterceptors(
                        new RefreshTokenInterceptor(redisTemplate),
                        new LoginInterceptor()
                )
                .build();
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void publicSuccessKeepsOldFieldsAndAddsZeroCode() throws Exception {
        mockMvc.perform(get("/shop/contract/ok"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value("ok"))
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void legacyBusinessFailureKeepsHttp200() throws Exception {
        expectError(get("/shop/contract/legacy-fail"), 200, 10000)
                .andExpect(jsonPath("$.errorMsg").value("既有业务失败"));
    }

    @Test
    void anonymousProtectedRequestReturnsJson401() throws Exception {
        expectError(get("/user/me"), 401, 40100)
                .andExpect(jsonPath("$.errorMsg").value("请先登录"));
    }

    @Test
    void missingRequiredParameterReturns400() throws Exception {
        expectError(get("/shop/contract/number"), 400, 40000)
                .andExpect(jsonPath("$.errorMsg").value("请求参数错误"));
    }

    @Test
    void invalidParameterTypeReturns400() throws Exception {
        expectError(
                get("/shop/contract/number").param("value", "abc"),
                400,
                40000
        );
    }

    @Test
    void malformedJsonReturns400() throws Exception {
        expectError(
                post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"),
                400,
                40000
        );//==========================================
        //这里故意发送不完整的 JSON，用来测试后端能否正确返回 400。
        //从 Java 的角度：
        //- "{" 是一个合法字符串。
        //- 字符串里的 { 不需要配对，也不需要转义。
        //- 这行 Java 写法正确。
        //从 JSON 的角度：
        //- { 表示一个对象开始。
        //- 后面缺少内容或结束符 }。
        //- 因此它是不合法的 JSON，IDEA 就会标红。
        //这个测试恰好需要这种不合法的输入。
    }

    @Test
    void unexpectedRuntimeExceptionReturnsSanitized500() throws Exception {
        expectError(get("/shop/contract/runtime"), 500, 50000)
                .andExpect(jsonPath("$.errorMsg").value("服务器异常"));
    }

    @Test
    void unexpectedCheckedExceptionReturnsSanitized500() throws Exception {
        expectError(get("/shop/contract/checked"), 500, 50000)
                .andExpect(jsonPath("$.errorMsg").value("服务器异常"));
    }

    @Test
    void unsupportedMethodKeeps405AndAllowHeader() throws Exception {
        expectError(post("/shop/contract/ok"), 405, 40500)
                .andExpect(header().exists("Allow"));
    }

    private ResultActions expectError(
            MockHttpServletRequestBuilder request,
            int expectedStatus,
            int expectedCode) throws Exception {

        return mockMvc.perform(request)
                .andExpect(status().is(expectedStatus))
                .andExpect(content()
                        .contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(expectedCode))
                .andExpect(jsonPath("$.errorMsg").isString());
    }

    @RestController
    static class ContractProbeController {

        @GetMapping("/shop/contract/ok")
        public Result ok() {
            return Result.ok("ok");
        }

        @GetMapping("/shop/contract/legacy-fail")
        public Result legacyFail() {
            return Result.fail("既有业务失败");
        }

        @GetMapping("/shop/contract/number")
        public Result number(@RequestParam("value") Long value) {
            return Result.ok(value);
        }

        @GetMapping("/shop/contract/runtime")
        public Result runtimeFailure() {
            throw new IllegalStateException("internal-runtime-detail");
        }

        @GetMapping("/shop/contract/checked")
        public Result checkedFailure() throws Exception {
            throw new Exception("internal-checked-detail");
        }

        @GetMapping("/user/me")
        public Result me() {
            return Result.ok(UserHolder.getUser());
        }

        @PostMapping("/user/login")
        public Result login(@RequestBody Map<String, Object> body) {
            return Result.ok(body);
        }
    }
}