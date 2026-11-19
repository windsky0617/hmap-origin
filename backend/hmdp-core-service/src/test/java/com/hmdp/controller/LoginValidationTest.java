package com.hmdp.controller;

import com.hmdp.config.WebExceptionAdvice;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.service.IUserInfoService;
import com.hmdp.service.IUserService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


//使用真实 UserController、真实校验器、现有 Advice，业务 Service 使用 Mock。重点验证无效请求在进入业务 Service 前就被拒绝
class LoginValidationTest {

    private MockMvc mockMvc;
    private IUserService userService;
    private IUserInfoService userInfoService;
    private LocalValidatorFactoryBean validator;

    @BeforeEach
    void setUp() {
        userService = mock(IUserService.class);
        userInfoService = mock(IUserInfoService.class);

        UserController controller = new UserController();
        ReflectionTestUtils.setField(controller, "userService", userService);
        ReflectionTestUtils.setField(
                controller, "userInfoService", userInfoService
        );

        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setValidator(validator)
                .setControllerAdvice(new WebExceptionAdvice())
                .build();
    }

    @AfterEach
    void tearDown() {
        validator.close();
    }

    @ParameterizedTest
    @MethodSource("invalidLoginBodies")
    void invalidFieldsReturn400BeforeCallingService(String body)
            throws Exception {

        mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.errorMsg").value("请求参数错误"))
                .andExpect(result -> assertInstanceOf(
                        MethodArgumentNotValidException.class,
                        result.getResolvedException()
                ));

        verifyNoInteractions(userService, userInfoService);
    }

    static Stream<String> invalidLoginBodies() {
        return Stream.of(
                "{}",
                """
                {"phone":null,"code":"123456"}
                """,
                """
                {"phone":"   ","code":"123456"}
                """,
                """
                {"phone":"13800000000"}
                """,
                """
                {"phone":"13800000000","code":null}
                """,
                """
                {"phone":"13800000000","code":""}
                """,
                """
                {"phone":"13800000000","code":"      "}
                """,
                """
                {"phone":"13800000000","code":"12345"}
                """,
                """
                {"phone":"13800000000","code":"1234567"}
                """,
                """
                {"phone":"13800000000","code":"12a456"}
                """
        );
    }

    @Test
    void validFieldsReachServiceWithoutChangingValues() throws Exception {
        when(userService.login(
                any(LoginFormDTO.class), any(HttpSession.class)
        )).thenReturn(Result.ok("test-session"));

        mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"13800000000","code":"123456"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value("test-session"));

        ArgumentCaptor<LoginFormDTO> captor =
                ArgumentCaptor.forClass(LoginFormDTO.class);

        verify(userService).login(
                captor.capture(), any(HttpSession.class)
        );

        assertEquals("13800000000", captor.getValue().getPhone());
        assertEquals("123456", captor.getValue().getCode());
        verifyNoInteractions(userInfoService);
    }

    @Test
    void expiredCodeStillUsesExistingBusinessFailureContract()
            throws Exception {

        when(userService.login(
                any(LoginFormDTO.class), any(HttpSession.class)
        )).thenReturn(Result.fail("验证码错误或已过期"));

        mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"13800000000","code":"123456"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(10000))
                .andExpect(jsonPath("$.errorMsg")
                        .value("验证码错误或已过期"));

        verify(userService).login(
                any(LoginFormDTO.class), any(HttpSession.class)
        );
    }

    @Test
    void passwordOnlyRequestDoesNotBypassCodeRequirement()
            throws Exception {

        mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"13800000000","password":"test-only"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(40000));

        verifyNoInteractions(userService, userInfoService);
    }
}