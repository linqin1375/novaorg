package io.github.tangbailin.novaorg.api;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;

final class TaskAccess {
    static Authentication authentication() { return SecurityContextHolder.getContext().getAuthentication(); }
    static boolean reviewer() {var a=authentication();return a!=null && a.getAuthorities().stream().anyMatch(x->x.getAuthority().equals("ROLE_ADMIN")||x.getAuthority().equals("ROLE_REVIEWER"));}
    static String user() {var a=authentication();if(a==null) throw new AccessDeniedException("Login required");return a.getName();}
    static void check(String owner) {if(!reviewer()&&!user().equals(owner)) throw new AccessDeniedException("不能访问其他用户的任务");}
}
