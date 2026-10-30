package com.coupon.common;

import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

// 버전별 컨트롤러 공통 동작 - 하위 클래스에서 @RequestMapping("/vN") 지정
@Slf4j
public abstract class CouponController {

    private final CouponIssuer issuer;
    private final BulkIssueSimulator bulkIssueSimulator;

    @Value("${SERVER_NAME:local}")
    private String serverName;

    // 하위 컨트롤러 생성자를 바꾸지 않도록 필드 주입
    @Autowired
    private DistributedIssueSimulator distributedIssueSimulator;

    protected CouponController(CouponIssuer issuer, BulkIssueSimulator bulkIssueSimulator) {
        this.issuer = issuer;
        this.bulkIssueSimulator = bulkIssueSimulator;
    }

    @GetMapping
    public String page(Model model) {
        addPageAttributes(model);
        return "coupon";
    }

    private void addPageAttributes(Model model) {
        model.addAttribute("version", issuer.version());
        model.addAttribute("description", issuer.description());
        model.addAttribute("stock", issuer.getStock());
        model.addAttribute("issuedCount", issuer.getIssuedCount());
        model.addAttribute("stockPercent", Math.max(0, issuer.getStock()) * 100 / CouponIssuer.INITIAL_STOCK);
        model.addAttribute("initialStock", CouponIssuer.INITIAL_STOCK);
    }

    @PostMapping("/issue")
    public String publish(RedirectAttributes redirectAttributes) {
        boolean issued = issuer.publish();
        redirectAttributes.addFlashAttribute("message", issued ? "쿠폰이 발급되었습니다." : "쿠폰이 모두 소진되었습니다.");
        return redirectToPage();
    }

    @PostMapping("/issue-bulk")
    public String publishBulk(RedirectAttributes redirectAttributes) throws InterruptedException {
        redirectAttributes.addFlashAttribute("bulkResult", bulkIssueSimulator.run(issuer));
        return redirectToPage();
    }

    // 서버 2대 대량 발급: nginx 경유 HTTP 1만 건
    // redirect 안 함: flash는 서버별 세션에 저장되는데 L4 nginx라 redirect GET이 다른 서버로 갈 수 있음
    @PostMapping("/issue-distributed")
    public String publishDistributed(Model model) throws InterruptedException {
        model.addAttribute("distributedResult",
                distributedIssueSimulator.run(issuer.version(), this::distributedIssuePath));
        addPageAttributes(model);
        return "coupon";
    }

    // 분산 대량 발급 시 요청 경로 (요청마다 호출, v7-2는 userId 포함 경로로 재정의)
    protected String distributedIssuePath() {
        return "/api/issue";
    }

    @PostMapping("/reset")
    public String reset() {
        issuer.reset();
        return redirectToPage();
    }

    private String redirectToPage() {
        return "redirect:/" + issuer.version();
    }

    @PostMapping("/api/issue")
    @ResponseBody
    public IssueResponse publishForMultiServer(HttpServletResponse response) {
        boolean issued = issuer.publish();

        if (issued) {
            response.setStatus(HttpServletResponse.SC_OK);
            log.debug("발급 성공");

            return new IssueResponse(true,  serverName, "success");
        } else {
            response.setStatus(HttpServletResponse.SC_CONFLICT); //발급 실패
            log.debug("발급 실패");

            return new IssueResponse(false, serverName, "error");
        }
    }

    public record IssueResponse(boolean isSuccess, String serverName, String message) {}
}
