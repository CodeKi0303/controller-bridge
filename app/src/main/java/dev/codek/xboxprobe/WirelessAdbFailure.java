package dev.codek.xboxprobe;

import java.io.IOException;

/** Sanitized failure at the boundary that owns each wireless ADB operation.
 * Raw exceptions may contain pairing material or bootstrap credentials, so only
 * their bounded class name is retained; neither their message nor cause escapes. */
final class WirelessAdbFailure extends IOException {
    enum Stage {
        PREPARATION("연결 준비", "앱의 연결 설정을 확인한 뒤 다시 시도해 주세요."),
        PAIRING_CONNECT("페어링 포트 연결", "설정의 페어링 코드 창을 열어 두고, 그 창의 포트를 확인해 주세요."),
        PAIRING_TLS("페어링 보안 연결", "페어링 코드 창을 새로 열고 현재 페어링 포트로 다시 시도해 주세요."),
        PAIRING_EXCHANGE("페어링 코드 인증", "현재 코드 창의 6자리 코드와 페어링 포트를 사용해 다시 시도해 주세요."),
        START_CONNECT("입력 서비스 연결 포트", "무선 디버깅을 켜고 기본 화면의 IP 주소 및 포트에 표시된 연결 포트를 확인해 주세요. 페어링 창의 포트와 다릅니다."),
        START_TLS_NEGOTIATION("입력 서비스 TLS 전환", "무선 디버깅 기본 화면의 연결 포트인지 확인해 주세요."),
        START_AUTHENTICATION("입력 서비스 기기 인증", "무선 디버깅의 페어링된 기기 목록을 확인해 주세요. 앱 키가 바뀌었거나 삭제됐다면 다시 페어링해야 합니다."),
        START_SHELL_OPEN("입력 서비스 실행 요청", "기기 인증 뒤 ADB 실행 요청이 완료되지 않았습니다. 무선 디버깅을 확인하고 다시 시작해 주세요."),
        START_BOOTSTRAP("입력 서비스 실행 응답", "인증 뒤 실행 통신이 중단됐습니다. 입력 서비스를 다시 시작해 주세요."),
        START_HANDOFF("입력 서비스 초기화", "기기 인증과 실행 요청 이후 앱 연결이 완료되지 않았습니다. 이 단계는 페어링 코드 오류와 구분됩니다.");

        final String label, advice;
        Stage(String label, String advice) { this.label=label; this.advice=advice; }
    }

    final Stage stage;
    final String exceptionType;

    private WirelessAdbFailure(Stage stage, String exceptionType) {
        super(stage.label+" 단계에서 실패했습니다 ("+exceptionType+"). "+stage.advice);
        this.stage=stage;
        this.exceptionType=exceptionType;
    }

    static WirelessAdbFailure at(Stage stage, Throwable failure) {
        if (failure instanceof WirelessAdbFailure) return (WirelessAdbFailure) failure;
        return new WirelessAdbFailure(stage, FailureTypes.className(failure));
    }

    static String userMessage(Throwable failure) {
        return at(Stage.PREPARATION, failure).getMessage();
    }

}
