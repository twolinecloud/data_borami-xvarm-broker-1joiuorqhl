package egovframework.broker.service;

import egovframework.broker.model.email.EmailAuth;
import egovframework.broker.response.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

@Log4j2
@Service
@RequiredArgsConstructor
public class LoginService {
    public Response<Object> doLogin(EmailAuth token) {
        return Response.of(token);
    }
}
