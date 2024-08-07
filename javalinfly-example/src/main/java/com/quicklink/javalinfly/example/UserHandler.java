package com.quicklink.javalinfly.example;


import com.quicklink.javalinfly.Response;
import com.quicklink.javalinfly.annotation.Body;
import com.quicklink.javalinfly.annotation.Controller;
import com.quicklink.javalinfly.annotation.Get;
import com.quicklink.javalinfly.annotation.Path;
import com.quicklink.javalinfly.annotation.Post;
import com.quicklink.javalinfly.annotation.Query;
import io.javalin.http.Context;

import java.util.List;
import org.jetbrains.annotations.NotNull;

@Controller(path = "/user") public class UserHandler {

//    @Post(responseType = ResponseType.STRING)
//    public Response<String, String> createUser(Context ctx, @Body User user) {
//
//        return Response.err("this is my create error");
//    }

    @Post(tags = {"user"})
    public Response<Users, StandardError> createAll(Context ctx, @Body Users users) {
        return Response.ok(users);
    }


    public void userTest () {

    }
    @Get(tags = "user")
    public Response<List<User<?>>, StandardError> getUser(Context ctx, @Path String id, @Query @NotNull String age) {
        return Response.ok(List.of(new User<>("denis", 2)));
    }

    public static class Users {
        public List<User<?>> users;


        public Users() {}
        public Users(List<User<?>> users) {
            this.users = users;
        }
    }

    public static class User<T> {

        public @NotNull String name;
        public int age;


        public User(@NotNull String name, int age) {
            this.name = name;
            this.age = age;
        }
    }


    public static class StandardError {
        public @NotNull String cause;

        public StandardError(@NotNull String cause) {
            this.cause = cause;
        }
    }


}

