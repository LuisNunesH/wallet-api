package com.walletapi.wallet.web;

import java.net.URI;
import java.time.OffsetDateTime;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.walletapi.wallet.WalletService;
import com.walletapi.wallet.domain.StatementQuery;
import com.walletapi.wallet.domain.TransactionCommand;
import com.walletapi.wallet.domain.TransactionResult;
import com.walletapi.wallet.domain.Wallet;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@RestController
@RequestMapping("/wallets")
@Tag(name = "Carteiras", description = "Criação, consulta, extrato e lançamentos de carteiras digitais")
public class WalletController {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    static final String IDEMPOTENT_REPLAYED_HEADER = "Idempotent-Replayed";

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @PostMapping
    @Operation(summary = "Cria uma carteira com saldo inicial 0.00")
    @ApiResponse(responseCode = "201", description = "Carteira criada")
    @ApiResponse(responseCode = "400", description = "Dados do titular inválidos",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<WalletResponse> createWallet(@Valid @RequestBody CreateWalletRequest request) {
        Wallet wallet = walletService.createWallet(request.holderName(), request.holderDocument());
        return ResponseEntity.created(URI.create("/wallets/" + wallet.id()))
                .body(WalletResponse.from(wallet));
    }

    @GetMapping("/{walletId}")
    @Operation(summary = "Consulta a carteira e o saldo atual")
    @ApiResponse(responseCode = "200", description = "Carteira encontrada")
    @ApiResponse(responseCode = "404", description = "Carteira não encontrada",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public WalletResponse getWallet(@PathVariable long walletId) {
        return WalletResponse.from(walletService.getWallet(walletId));
    }

    @GetMapping("/{walletId}/transactions")
    @Operation(summary = "Extrato paginado da carteira, do lançamento mais recente para o mais antigo")
    @ApiResponse(responseCode = "200", description = "Página do extrato")
    @ApiResponse(responseCode = "400", description = "Parâmetros de paginação ou período inválidos",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Carteira não encontrada",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public PageResponse<TransactionResponse> getStatement(
            @PathVariable long walletId,
            @Parameter(description = "Início do período (inclusivo), ISO-8601", example = "2026-11-01T00:00:00Z")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @Parameter(description = "Fim do período (exclusivo), ISO-8601", example = "2026-12-01T00:00:00Z")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "deve ser maior ou igual a 0") int page,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "deve ser maior ou igual a 1")
            @Max(value = 100, message = "deve ser menor ou igual a 100") int size) {
        StatementQuery query = new StatementQuery(walletId, from, to, page, size);
        return PageResponse.from(walletService.getStatement(query), TransactionResponse::from);
    }

    @GetMapping("/{walletId}/transactions/{transactionId}")
    @Operation(summary = "Consulta um lançamento específico do extrato")
    @ApiResponse(responseCode = "200", description = "Lançamento encontrado")
    @ApiResponse(responseCode = "404", description = "Lançamento não encontrado nesta carteira",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public TransactionResponse getTransaction(@PathVariable long walletId, @PathVariable long transactionId) {
        return TransactionResponse.from(walletService.getTransaction(walletId, transactionId));
    }

    @PostMapping("/{walletId}/transactions")
    @Operation(summary = "Registra um crédito ou débito de forma idempotente",
            description = "Repetir a requisição com a mesma Idempotency-Key e o mesmo payload devolve o resultado "
                    + "original sem alterar o saldo. A mesma chave com payload diferente retorna 409.")
    @ApiResponse(responseCode = "201", description = "Lançamento aplicado, ou resultado original em caso de retry",
            headers = @Header(name = IDEMPOTENT_REPLAYED_HEADER,
                    description = "true quando a resposta é a repetição de uma operação já aplicada",
                    schema = @Schema(type = "boolean")))
    @ApiResponse(responseCode = "400", description = "Payload ou Idempotency-Key inválidos",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Carteira não encontrada",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Idempotency-Key reutilizada com outro payload, ou carteira ocupada",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "Saldo insuficiente ou limite de saldo excedido",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<TransactionResponse> registerTransaction(
            @PathVariable long walletId,
            @Parameter(description = "Chave única da operação nesta carteira (ex.: UUID)", required = true)
            @RequestHeader(IDEMPOTENCY_KEY_HEADER)
            @NotBlank(message = "é obrigatório")
            @Pattern(regexp = "^[A-Za-z0-9_.:-]{1,64}$",
                    message = "deve ter até 64 caracteres alfanuméricos ou _ . : -") String idempotencyKey,
            @Valid @RequestBody RegisterTransactionRequest request) {
        TransactionCommand command = new TransactionCommand(
                walletId, idempotencyKey, request.type(), request.amount(), request.description());
        TransactionResult result = walletService.registerTransaction(command);
        return ResponseEntity.created(URI.create("/wallets/" + walletId + "/transactions/" + result.transaction().id()))
                .header(IDEMPOTENT_REPLAYED_HEADER, String.valueOf(result.replayed()))
                .body(TransactionResponse.from(result.transaction()));
    }
}
