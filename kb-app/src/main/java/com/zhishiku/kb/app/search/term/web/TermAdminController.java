package com.zhishiku.kb.app.search.term.web;

import com.zhishiku.kb.app.search.term.CorrectionWriteService;
import com.zhishiku.kb.app.search.term.TermException;
import com.zhishiku.kb.app.search.term.TermInput;
import com.zhishiku.kb.app.search.term.TermReadService;
import com.zhishiku.kb.app.search.term.TermReceipt;
import com.zhishiku.kb.app.search.term.TermReceiptService;
import com.zhishiku.kb.app.search.term.TermViews;
import com.zhishiku.kb.app.search.term.TermWriteService;
import com.zhishiku.kb.common.api.R;
import com.zhishiku.kb.common.security.SearchTermEndpoints;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 管理入口只适配协议，读写各由对应服务当前资格保护，不共享资源权限推断。 */
@RestController
@RequestMapping(value = SearchTermEndpoints.PREFIX, produces = MediaType.APPLICATION_JSON_VALUE)
public class TermAdminController {
    private final TermReadService reads;
    private final TermWriteService terms;
    private final CorrectionWriteService corrections;
    private final TermReceiptService receipts;

    public TermAdminController(TermReadService reads, TermWriteService terms, CorrectionWriteService corrections, TermReceiptService receipts) {
        this.reads = reads;
        this.terms = terms;
        this.corrections = corrections;
        this.receipts = receipts;
    }

    @PostMapping(value = "/terms/query", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<R<TermViews.Page<TermViews.Term>>> terms(HttpServletRequest request) {
        return success(reads.terms(TermInput.query(TermBody.read(request))));
    }

    @GetMapping("/terms/{id}")
    public ResponseEntity<R<TermViews.TermDetail>> term(@PathVariable String id, HttpServletRequest request) {
        TermBody.rejectQuery(request);
        return success(reads.term(TermInput.id(id)));
    }

    @PostMapping(value = "/terms", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<R<TermReceipt>> createTerm(HttpServletRequest request, @RequestHeader("X-Request-Id") String requestId) {
        return success(terms.write(null, TermInput.term(TermBody.read(request), true), TermInput.requestId(requestId)));
    }

    @PutMapping(value = "/terms/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<R<TermReceipt>> updateTerm(@PathVariable String id, HttpServletRequest request,
                                                    @RequestHeader("X-Request-Id") String requestId) {
        return success(terms.write(TermInput.id(id), TermInput.term(TermBody.read(request), false), TermInput.requestId(requestId)));
    }

    @PatchMapping(value = "/terms/{id}/enabled", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<R<TermReceipt>> enableTerm(@PathVariable String id, HttpServletRequest request,
                                                    @RequestHeader("X-Request-Id") String requestId) {
        return success(terms.enabled(TermInput.id(id), TermInput.enabled(TermBody.read(request)), TermInput.requestId(requestId)));
    }

    @PostMapping(value = "/terms/{id}/meanings/query", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<R<TermViews.Page<TermViews.Meaning>>> meanings(@PathVariable String id, HttpServletRequest request) {
        return success(reads.meanings(TermInput.id(id), TermInput.meaningQuery(TermBody.read(request))));
    }

    @PostMapping(value = "/corrections/query", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<R<TermViews.Page<TermViews.Correction>>> corrections(HttpServletRequest request) {
        return success(reads.corrections(TermInput.query(TermBody.read(request))));
    }

    @GetMapping("/corrections/{id}")
    public ResponseEntity<R<TermViews.CorrectionDetail>> correction(@PathVariable String id, HttpServletRequest request) {
        TermBody.rejectQuery(request);
        return success(reads.correction(TermInput.id(id)));
    }

    @PostMapping(value = "/corrections", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<R<TermReceipt>> createCorrection(HttpServletRequest request, @RequestHeader("X-Request-Id") String requestId) {
        return success(corrections.write(null, TermInput.correction(TermBody.read(request), true), TermInput.requestId(requestId)));
    }

    @PutMapping(value = "/corrections/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<R<TermReceipt>> updateCorrection(@PathVariable String id, HttpServletRequest request,
                                                          @RequestHeader("X-Request-Id") String requestId) {
        return success(corrections.write(TermInput.id(id), TermInput.correction(TermBody.read(request), false), TermInput.requestId(requestId)));
    }

    @PatchMapping(value = "/corrections/{id}/enabled", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<R<TermReceipt>> enableCorrection(@PathVariable String id, HttpServletRequest request,
                                                          @RequestHeader("X-Request-Id") String requestId) {
        return success(corrections.enabled(TermInput.id(id), TermInput.enabled(TermBody.read(request)), TermInput.requestId(requestId)));
    }

    @PostMapping(value = "/commands/query", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<R<TermReceipt>> command(HttpServletRequest request) {
        return success(receipts.query(TermInput.commandQuery(TermBody.read(request))));
    }

    private static <T> ResponseEntity<R<T>> success(T value) {
        if (value == null) {
            throw new TermException(TermException.Reason.UNAVAILABLE);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(R.ok(value));
    }
}
