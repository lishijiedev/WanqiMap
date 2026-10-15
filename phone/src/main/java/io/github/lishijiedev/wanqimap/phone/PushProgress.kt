package io.github.lishijiedev.wanqimap.phone

/**
 * 一次推送的三个步骤，以及每个步骤的状态 —— 供手机界面的进度列表使用。
 *
 * 为什么要把它做成枚举，而不是让界面去解析 [DestinationGattServer] 传出来的提示文字：
 * GATT 那侧的回调顺序（开广播成功 → 手表连接并订阅 → 分片发完 → 收到回执）本来就与
 * 这三个步骤一一对应，是**结构**上的事实；而提示文字只是为了给人看的，改一个措辞
 * 就会把界面判断弄坏。发送方最清楚自己走到哪一步，直接说出步骤名，界面照着画即可。
 */
enum class PushStep(val labelRes: Int) {

    /** 手机已开始广播，等手表扫到并连上来。 */
    ADVERTISING(R.string.step_advertising),

    /** 手表已连接并订阅，正在把目的地分片发过去。 */
    SENDING(R.string.step_sending),

    /** 目的地已发完，等手表回执确认它已开始导航。 */
    CONFIRMING(R.string.step_confirming)
}

enum class StepState {

    /** 还没到这一步。 */
    TODO,

    /** 正卡在这一步 —— 用户最需要知道的就是这个。 */
    ACTIVE,

    /** 已完成。 */
    DONE
}
