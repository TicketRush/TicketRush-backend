package com.ticketrush.global.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InboxRetentionBatchDeleterTest {

  @InjectMocks private InboxRetentionBatchDeleter batchDeleter;

  @Mock private InboxRepository inboxRepository;

  @Test
  @DisplayName("배치 삭제를 repository의 LIMIT 삭제 쿼리에 위임하고 삭제 수를 반환한다")
  void deleteBatch_delegates_to_repository() {
    // given
    LocalDateTime threshold = LocalDateTime.now().minusDays(30);
    int batchSize = 1000;
    given(inboxRepository.deleteCreatedBeforeInBatch(threshold, batchSize)).willReturn(1000);

    // when
    int deleted = batchDeleter.deleteBatch(threshold, batchSize);

    // then
    assertThat(deleted).isEqualTo(1000);
    verify(inboxRepository).deleteCreatedBeforeInBatch(threshold, batchSize);
  }
}
