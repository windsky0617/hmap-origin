package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;


    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
                }
                );
        return Result.ok(records);

    }

    @Override
    public Result queryBlogById(Long id) {

        //1.查询blog
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在！");
        }

        //2.查询blog有关的用户
        queryBlogUser(blog);
        //3.查询当前登录用户是否已经点过赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        // 1. 获取登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            //用户未登录，无需查询是否点赞
            return ;
        }
        Long userId =user.getId();
        // 2. 判断当前登录用户是否已经点赞
        String key = "blog:liked:" + blog.getId();
        //去redis里查询这个blog已经点赞了的set集合里有没有这个用户的id
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
        //我们查到数据库的 blog 对象之后，额外去 Redis 查当前登录用户的点赞状态，把查到的 true/false 塞到 blog 对象的 isLike 属性里，再整体返回给前端。
        //由前端来判断，数据库的 blog 表里面没有 isLike 这个字段，isLike 是我们在 Blog 实体类上额外加的一个【临时标记字段】，
        // 专门用来告诉前端：当前这个打开页面的人，有没有给这条笔记点过赞。

    }

    @Override
    public Result likeBlog(Long id) {

        // 1. 获取登录用户
        Long userId = UserHolder.getUser().getId();
        // 2. 判断当前登录用户是否已经点赞
        String key = BLOG_LIKED_KEY + id;
        //去redis里查询这个blog已经点赞了的set集合里有没有这个用户的id
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        if(BooleanUtil.isFalse(isMember)) {
            // 3. 如果未点赞，可以点赞
            // 3.1. 数据库点赞数 + 1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            // 3.2. 保存用户到Redis的Zset集合,根据点赞先后进行排序
            if (isSuccess) {                    //zadd key menber(value)  score
                stringRedisTemplate.opsForZSet().add(key, userId.toString(),System.currentTimeMillis());
            }
        }else {
            // 4. 如果已点赞，取消点赞
            // 4.1. 数据库点赞数 -1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            // 4.2. 把用户从Redis的set集合移除
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();

    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = BLOG_LIKED_KEY + id;
// 1. 查询top5的点赞用户 zrange key 0 4
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5==null || top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
// 2. 解析出其中的用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
// 3. 根据用户id查询用户  where id in (5,1) order by field(id , 5 , 1 )
        List<UserDTO> userDTOS = userService.query()
                .in("id",ids)
                .last("ORDER BY FIELD(id,"+ idStr +")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
// 4. 返回
        return Result.ok(userDTOS);

    }

    @Override
    public Result saveBlog(Blog blog) {

        //1.获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        //2.保存探店博文
        boolean isSuccess = save(blog);
        if (!isSuccess){
            return Result.fail("新增笔记失败！");
        }
        //3.查询笔记作者的所有粉丝
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();

        //4，推送笔记id给所有粉丝
        for(Follow follow:follows ){
            //4.1获取粉丝id
            Long userId = follow.getId();
            //4.2推送
            String key = FEED_KEY +userId;    //粉丝的收件箱      投送的笔记的id    根据时间戳排序
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
        }
        //5.返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {//实现分页查询
        // 1.获取当前用户
        Long userId = UserHolder.getUser().getId();
// 2. 查询收件箱 ZREVRANGEBYSCORE key Max Min LIMIT offset count
//      offset:从上一次查询到的元素的末尾，大于等于他本身的第offset个（0：本身，1：后一个），查询count个
        String key = FEED_KEY + userId;//获取收件箱的key
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);//查收件箱里已经按时间先后排序的blog
// 3. 非空判断
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
// 4. 解析拿到的blog的数据: blogId、minTime（时间戳）、offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) { //5 4 4 2 2
            // 4.1.获取id
            ids.add(Long.valueOf(tuple.getValue()));
            // 4.2.获取分数(时间戳）
            Long time = tuple.getScore().longValue();
            if (time == minTime){
                os++; //判断到一样的，offset+1，说明此时最小时间戳后面有一个和他一样的，offset+1来跳过这个一样的
            }else {
                minTime = time;//不等，用新的最小覆盖之前的最小
                os = 1;//offset重置，说嘛此时已经查到最小的时间戳了，后面没有和这个最小一样的，下一次只需要查他后面那一个
            }
        }
        // 4. 根据id查询blog

        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();

        for (Blog blog : blogs) {
            // 5.1查询blog有关的用户
            queryBlogUser(blog);
            // 5.2查询blog是否被点赞
            isBlogLiked(blog);
        }

        // 6.封装并返回
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(os);
        r.setMinTime(minTime);

        return Result.ok(r);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());//拿名字
        blog.setIcon(user.getIcon());//拿头像
    }
}
