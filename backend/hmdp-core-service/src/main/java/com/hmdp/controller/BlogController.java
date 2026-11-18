package com.hmdp.controller;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import jakarta.validation.constraints.Min;
import java.util.List;


@RestController
@RequestMapping("/blog")
public class BlogController {

    @Resource
    private IBlogService blogService;


    @PostMapping
    public Result saveBlog(@RequestBody Blog blog) {
        return  blogService.saveBlog(blog);
    }

    @PutMapping("/like/{id}")
    public Result likeBlog(@PathVariable("id") Long id) {
        return blogService.likeBlog(id);
    }

    @GetMapping("/of/me")
    public Result queryMyBlog(
            //@Min(1)    → 值 >= 1
            //@Max(100)  → 值 <= 100
            //`message = "页码不能小于1"`：**校验不通过时，返回给前端的错误提示文本**
            @Min(value = 1, message = "页码不能小于1")
            @RequestParam(value = "current", defaultValue = "1") Integer current) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .eq("user_id", user.getId()).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    @GetMapping("/hot")
    public Result queryHotBlog(
            @Min(value = 1, message = "页码不能小于1")
            @RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogService.queryHotBlog(current);
    }


    @GetMapping("/{id}")
    public Result queryBlogById(@PathVariable("id") Long id) {
        return blogService.queryBlogById(id);
    }


    //查询blog点赞的所有人
    @GetMapping("/likes/{id}")
    public Result queryBloglikes(@PathVariable("id") Long id) {
        return blogService.queryBlogLikes(id);
    }

    //根据用户 id 查询笔记
    @GetMapping("/of/user")
    public Result queryBlogByUserId(
            @Min(value = 1, message = "页码不能小于1")
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam("id") Long id) {
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .eq("user_id", id).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }


    @GetMapping("/of/follow")
    public Result queryBloglOfFollow(
            @Min(value = 0, message = "时间游标不能小于0")
            @RequestParam("lastId") Long max,
            @Min(value = 0, message = "偏移量不能小于0")
            @RequestParam("offset") Integer offset) {
        return blogService.queryBlogOfFollow(max,offset);
    }




}