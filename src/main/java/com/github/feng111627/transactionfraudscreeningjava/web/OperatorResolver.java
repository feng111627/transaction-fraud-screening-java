package com.github.feng111627.transactionfraudscreeningjava.web;

import com.github.feng111627.transactionfraudscreeningjava.error.ValidationException;
import com.github.feng111627.transactionfraudscreeningjava.model.Operator;
import com.github.feng111627.transactionfraudscreeningjava.model.Role;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/**
 * 本地鉴权：从请求头解析操作人，仅用于本地演示。
 * <ul>
 *   <li>{@code X-Operator}: 操作人标识；</li>
 *   <li>{@code X-Roles}: 逗号分隔的角色，取值 ANALYST / SENIOR_ANALYST / ADMIN。</li>
 * </ul>
 */
@Component
public class OperatorResolver {

    public Operator resolve(HttpServletRequest request) {
        String name = request.getHeader("X-Operator");
        if (name == null || name.isBlank()) {
            throw new ValidationException("OPERATOR_MISSING", "缺少 X-Operator 请求头，无法识别操作人");
        }
        String rolesHeader = request.getHeader("X-Roles");
        Set<Role> roles = EnumSet.noneOf(Role.class);
        if (rolesHeader != null && !rolesHeader.isBlank()) {
            for (String token : Arrays.stream(rolesHeader.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList()) {
                try {
                    roles.add(Role.valueOf(token));
                } catch (IllegalArgumentException e) {
                    throw new ValidationException("ROLE_INVALID", "未知角色: " + token);
                }
            }
        }
        return new Operator(name.trim(), roles);
    }
}
