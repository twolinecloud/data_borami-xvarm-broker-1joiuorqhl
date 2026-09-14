package com.correction.borami.xvarm.adapter;

import com.correction.borami.xvarm.model.ExtractDtos.ExtractRequest;

import java.nio.file.Path;

/**
 * XVARM 솔루션을 호출해 파일을 임시 폴더에 꺼내 놓는다.
 *
 * <p>구현을 인터페이스로 끊어 둔 이유: <b>XVARM 의 실제 호출 사양이 아직 없다</b>(계획서 Q3).
 * 사양이 나오면 {@link com.correction.borami.xvarm.adapter.RealXvarmAdapter} 만 채우면 되고,
 * 컨트롤러·서비스·API 규격은 손대지 않는다.</p>
 */
public interface XvarmAdapter {

    /**
     * 추출을 수행한다(동기). 서비스가 별도 스레드에서 호출한다.
     *
     * @return 생성된 파일 경로
     * @throws Exception 추출 실패
     */
    Path extract(ExtractRequest request, Path outputDir) throws Exception;

    String mode();
}
