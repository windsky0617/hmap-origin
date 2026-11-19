package com.hmdp.controller;

import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.config.WebExceptionAdvice;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.service.IBlogService;
import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.Collections;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BlogPaginationValidationTest {

    private MockMvc mockMvc;
    private IBlogService blogService;
    private LocalValidatorFactoryBean validator;

    @BeforeEach
    void setUp() {
        UserHolder.removeUser();

        UserDTO user = new UserDTO();
        user.setId(1L);
        UserHolder.saveUser(user);

        blogService = mock(IBlogService.class);

        BlogController controller = new BlogController();
        ReflectionTestUtils.setField(controller, "blogService", blogService);

        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setValidator(validator)
                .setControllerAdvice(new WebExceptionAdvice())
                .addInterceptors(new LoginInterceptor())
                .build();
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
        validator.close();
    }

    static Stream<Arguments> invalidPageRequests() {
        return Stream.of("/blog/hot", "/blog/of/me", "/blog/of/user")
                .flatMap(path -> Stream.of("0", "-1", "abc")
                        .map(current -> Arguments.of(path, current)));
    }

    @ParameterizedTest
    @MethodSource("invalidPageRequests")
    void invalidPageReturns400BeforeCallingService(
            String path, String current) throws Exception {

        MockHttpServletRequestBuilder request = get(path)
                .param("current", current);

        if ("/blog/of/user".equals(path)) {
            request.param("id", "2");
        }

        mockMvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.errorMsg").value("请求参数错误"));

        verifyNoInteractions(blogService);
    }

    static Stream<Arguments> validOwnAndUserPages() {
        return Stream.of(
                Arguments.of("/blog/of/me", null, 1L, 1L),
                Arguments.of("/blog/of/me", "2", 2L, 1L),
                Arguments.of("/blog/of/user", null, 1L, 2L),
                Arguments.of("/blog/of/user", "2", 2L, 2L)
        );
    }

    @ParameterizedTest
    @MethodSource("validOwnAndUserPages")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void validPageKeepsCurrentUserAndPageSize(
            String path,
            String current,
            long expectedCurrent,
            long expectedUserId) throws Exception {

        QueryChainWrapper<Blog> query = mock(QueryChainWrapper.class);
        when(blogService.query()).thenReturn(query);
        when(query.eq("user_id", expectedUserId)).thenReturn(query);

        when(query.page(any(Page.class))).thenAnswer(invocation -> {
            Page<Blog> page = invocation.getArgument(0);
            assertEquals(expectedCurrent, page.getCurrent());
            assertEquals(
                    (long) SystemConstants.MAX_PAGE_SIZE,
                    page.getSize()
            );
            page.setRecords(Collections.emptyList());
            return page;
        });

        MockHttpServletRequestBuilder request = get(path);
        if (current != null) {
            request.param("current", current);
        }
        if ("/blog/of/user".equals(path)) {
            request.param("id", Long.toString(expectedUserId));
        }

        mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray());

        verify(query).eq("user_id", expectedUserId);
        verify(query).page(any(Page.class));
    }

    @Test
    void anonymousHotRequestDefaultsToPageOne() throws Exception {
        UserHolder.removeUser();

        when(blogService.queryHotBlog(1))
                .thenReturn(Result.ok(Collections.emptyList()));

        mockMvc.perform(get("/blog/hot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray());

        verify(blogService).queryHotBlog(1);
    }

    @ParameterizedTest
    @CsvSource({"-1,0", "0,-1", "-1,-1"})
    void negativeFeedCursorReturns400BeforeCallingService(
            String lastId, String offset) throws Exception {

        mockMvc.perform(get("/blog/of/follow")
                        .param("lastId", lastId)
                        .param("offset", offset))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.errorMsg").value("请求参数错误"));

        verifyNoInteractions(blogService);
    }

    static Stream<Arguments> validFeedRequests() {
        return Stream.of(
                Arguments.of(0L, 0),
                Arguments.of(1700000000000L, 2)
        );
    }

    @ParameterizedTest
    @MethodSource("validFeedRequests")
    void validFeedCursorReachesServiceUnchanged(
            long lastId, int offset) throws Exception {

        ScrollResult result = new ScrollResult();
        result.setList(Collections.emptyList());
        result.setMinTime(0L);
        result.setOffset(0);

        when(blogService.queryBlogOfFollow(lastId, offset))
                .thenReturn(Result.ok(result));

        mockMvc.perform(get("/blog/of/follow")
                        .param("lastId", Long.toString(lastId))
                        .param("offset", Integer.toString(offset)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list").isArray())
                .andExpect(jsonPath("$.data.minTime").value(0))
                .andExpect(jsonPath("$.data.offset").value(0));

        verify(blogService).queryBlogOfFollow(lastId, offset);
    }

    @Test
    void missingFeedOffsetKeepsRequiredParameterContract()
            throws Exception {

        mockMvc.perform(get("/blog/of/follow")
                        .param("lastId", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(40000));

        verifyNoInteractions(blogService);
    }

    @Test
    void anonymousFeedReturns401BeforeParameterValidation()
            throws Exception {

        UserHolder.removeUser();

        mockMvc.perform(get("/blog/of/follow")
                        .param("lastId", "-1")
                        .param("offset", "-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(40100));

        verifyNoInteractions(blogService);
    }
}