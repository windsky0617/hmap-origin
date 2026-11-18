package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import jakarta.validation.constraints.Positive;


@RestController
@RequestMapping("/follow")
public class FollowController {

    @Resource
    private IFollowService followService;

    //要关注的人的id  是否关注
    @PutMapping("/{id}/{isFollow}")
    public Result follow(
            @Positive(message = "目标用户ID必须大于0")
            @PathVariable("id") Long followUserId,
            @PathVariable("isFollow") Boolean isFollow){
        return followService.follow(followUserId,isFollow);

    }

    //当前用户是否关注了这个人，用于返回true/false给前端切换 关注/取消关注
    @GetMapping("/or/not/{id}")
    public Result isFollow(
            @Positive(message = "目标用户ID必须大于0")
            @PathVariable("id") Long followUserId ){
        return followService.isFollow(followUserId);

    }

    @GetMapping("/common/{id}")
    public Result follow(
            @Positive(message = "目标用户ID必须大于0")
            @PathVariable("id") Long id ){
        return followService.followCommon(id);

    }






}