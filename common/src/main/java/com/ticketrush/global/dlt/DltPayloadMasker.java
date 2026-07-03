package com.ticketrush.global.dlt;

import java.util.regex.Pattern;

/**
 * {@code dead_letter_record} 저장 직전 payload/exceptionMessage에서 PII를 마스킹하는 경량 유틸(#307).
 *
 * <p>키관리 없이 조회·수동복구 가능성을 유지하기 위해 암호화 대신 정규식 마스킹만 적용한다. <b>보수적 패턴만</b> 마스킹해 정상 식별자(예: {@code
 * bookingId=100}, {@code userId=5} 같은 짧은 숫자열)를 PII로 <b>오탐하지 않도록</b> 한다. 오탐은 실패 원인 추적을 훼손하므로, 카드번호는
 * 구분자(하이픈·공백)로 나뉜 4-4-4-4 형태처럼 명확한 것만 매칭한다.
 *
 * <ul>
 *   <li>이메일: 로컬파트 첫 글자만 남기고 도메인까지 마스킹({@code user@x.com} → {@code u***@***}).
 *   <li>카드번호: 구분자로 구분된 4-4-4-4(마지막 그룹 3~4자리) 형태만. 마지막 4자리만 남긴다({@code ****-****-****-1234}).
 *   <li>한국 휴대폰: {@code 01[016789]} 시작 번호의 가운데를 마스킹({@code 010-****-5678}).
 *   <li>주민등록번호: {@code YYMMDD-[1-4]NNNNNN} 형태(성별자리 [1-4] 강제)만 매칭해 뒷자리 6개를 마스킹({@code
 *       900101-1******}).
 * </ul>
 */
public final class DltPayloadMasker {

  private static final Pattern EMAIL =
      Pattern.compile("([\\w.+-])[\\w.+-]*@[\\w.-]+\\.[A-Za-z]{2,}");

  /** 구분자(하이픈·공백)로 나뉜 4-4-4-4(마지막 3~4자리) 카드 형태만 보수적으로 매칭한다. */
  private static final Pattern CARD =
      Pattern.compile("\\b\\d{4}[ -]\\d{4}[ -]\\d{4}[ -]\\d{3,4}\\b");

  /** 한국 휴대폰 번호({@code 01[016789]-3~4자리-4자리}, 구분자 선택). */
  private static final Pattern PHONE = Pattern.compile("\\b01[016789][ -]?\\d{3,4}[ -]?\\d{4}\\b");

  /**
   * 주민등록번호: 생년월일 6자리, 하이픈, 성별자리([1-4]) + 6자리.
   *
   * <p>성별자리를 {@code [1-4]}로 제한해 임의의 {@code 6자리-7자리} 숫자열을 오탐하지 않는다. 뒷자리 6개를 {@code ******}으로 마스킹하고
   * 성별자리는 보존({@code 900101-1******}).
   */
  private static final Pattern RRN = Pattern.compile("\\b(\\d{6})-([1-4])\\d{6}\\b");

  private DltPayloadMasker() {}

  /**
   * 입력 문자열에서 이메일·카드번호·휴대폰 번호·주민등록번호를 마스킹한다. null 입력은 null을 반환한다.
   *
   * @param input 원본 문자열(payload 또는 예외 메시지)
   * @return 마스킹된 문자열, 입력이 null이면 null
   */
  public static String mask(String input) {
    if (input == null) {
      return null;
    }
    String result = EMAIL.matcher(input).replaceAll(mr -> mr.group(1) + "***@***");
    result = CARD.matcher(result).replaceAll(mr -> "****-****-****-" + lastFour(mr.group()));
    result = PHONE.matcher(result).replaceAll(mr -> maskPhone(mr.group()));
    result = RRN.matcher(result).replaceAll(mr -> mr.group(1) + "-" + mr.group(2) + "******");
    return result;
  }

  private static String lastFour(String matched) {
    String digits = matched.replaceAll("\\D", "");
    return digits.substring(digits.length() - 4);
  }

  private static String maskPhone(String matched) {
    String digits = matched.replaceAll("\\D", "");
    return digits.substring(0, 3) + "-****-" + digits.substring(digits.length() - 4);
  }
}
