package server.nadeliv.users.service.LoginServiceImpl;

import io.jsonwebtoken.Claims;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.util.NullableUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import server.nadeliv.error.CustomException;
import server.nadeliv.error.ErrorCode;
import server.nadeliv.users.dto.CustomUserDetails;
import server.nadeliv.users.dto.TokenDTO;
import server.nadeliv.users.dto.UsersDTO;
import server.nadeliv.users.model.Users;
import server.nadeliv.users.repo.UsersRepo;
import server.nadeliv.users.service.EmailVerificationService;
import server.nadeliv.users.service.LoginService;
import server.nadeliv.utils.JwtUtil;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Service
public class LoginServiceImpl implements LoginService {
    // ✅ JwtUtil을 의존성 주입으로 받기
    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private UsersRepo usersRepo;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private EmailVerificationService emailVerificationService;

    private Users findUserByUserId(String userId) {
        return usersRepo.findByUserId(userId);
    }

    @Override
    public UsersDTO createUser(UsersDTO usersDTO) throws Exception {
        try {
            // 이메일 인증 여부 확인
            if (!emailVerificationService.isVerified(usersDTO.getEmail())) {
                throw new CustomException(ErrorCode.EMAIL_NOT_VERIFIED);
            }

            // validation 체크
            Users existingUser = usersRepo.findByUserId(usersDTO.getUserId());
            if (!ObjectUtils.isEmpty(existingUser)) {
                throw new CustomException(ErrorCode.USER_ALREADY_EXISTS, "이미 존재하는 ID입니다");
            }

            existingUser = usersRepo.findByEmail(usersDTO.getEmail());
            if (!ObjectUtils.isEmpty(existingUser)) {
                throw new CustomException(ErrorCode.EMAIL_ALREADY_EXISTS);
            }

            LocalDateTime now = LocalDateTime.now();

            // new Users()를 사용하면 UserCommon의 기본값이 자동 적용됨
            Users newUser = new Users();
            newUser.setUserId(usersDTO.getUserId());
            newUser.setUserPassword(passwordEncoder.encode(usersDTO.getUserPassword()));
            newUser.setEmail(usersDTO.getEmail());
            newUser.setName(usersDTO.getName());
            newUser.setBirthday(usersDTO.getBirthday());
            newUser.setSrc(usersDTO.getSrc());
            newUser.setRoles(Arrays.asList("user"));
            newUser.setAuthorities(Arrays.asList("user"));
            newUser.setCreated(now);
            newUser.setUpdated(now);

            Users savedUser = usersRepo.save(newUser);

            // 회원가입 성공 시 인증 완료 플래그 삭제
            emailVerificationService.clearVerified(usersDTO.getEmail());

            UsersDTO responseDTO = new UsersDTO();
            BeanUtils.copyProperties(savedUser, responseDTO);
            responseDTO.setUserPassword(null);

            return responseDTO;

        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            throw e;
        }
    }

    // refresh token verify, access token 재생성
    @Override
    public TokenDTO refreshToken(String refreshToken) throws CustomException, Exception {
        try {
            TokenDTO newTokenDTO = new TokenDTO();
            Claims claims = jwtUtil.verifyToken(refreshToken);
            if(!claims.getSubject().isEmpty()) {
                String userId = claims.getSubject();
                // 탈퇴(비활성)한 계정은 이전에 받은 refresh 토큰으로도 세션을 이어갈 수 없다
                Users user = usersRepo.findByUserId(userId);
                if (user == null || !user.isEnabled()) {
                    throw new CustomException(ErrorCode.ACCOUNT_DISABLED);
                }
                String newAccessToken  = jwtUtil.generateAccessToken(userId);
                newTokenDTO.setRefreshToken(refreshToken);
                newTokenDTO.setAccessToken(newAccessToken);
            }
            return newTokenDTO;
        } catch (CustomException e) {
            String message = e.getMessage();
            if (message.contains("Token expired")) {
                throw e;
            } else {
                // 잘못된 리프레시 토큰인 경우
                throw e;
            }
        } catch (Exception e) {
            throw e;
        }
    }

    // 로그인한 사용자 정보
    @Override
    public UsersDTO loginUser() throws Exception {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            System.out.println("authentication = " + authentication);
            if (authentication != null && authentication.getPrincipal() != null) {
                UserDetails userDetails = (UserDetails) authentication.getPrincipal();
                System.out.println("userDetails = " + userDetails);
                String username = userDetails.getUsername();

                Users users = usersRepo.findByUserId(username);
                if (users != null) {
                    UsersDTO usersDTO = new UsersDTO();
                    BeanUtils.copyProperties(users, usersDTO);
                    // 비밀번호는 응답에서 제외
                    usersDTO.setUserPassword(null);
                    return usersDTO;
                } else {
                    return null;
                }
            } else {
                return null;
            }
        } catch (Exception e) {
            throw e;
        }
    }

    @Override
    public void logout() throws Exception {
        try {
            SecurityContextHolder.clearContext();
        } catch (Exception e) {
            throw e;
        }
    }
}