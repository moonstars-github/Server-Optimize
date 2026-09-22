专为极致性能打造的Minecraft Tick计算优化模组。<br>

A specialized optimization mod for Minecraft’s tick system, engineered to deliver peak performance.<br><br>

本模组大量使用AI制作, 且属于早期开发阶段, 可能会影响部分特性（一些已在配置项标注）, 安装前务必备份你的存档<br>

This mod makes extensive use of AI and is still in its early development stages, which may affect certain features (as indicated in the configuration settings). Be sure to back up your world before installing it.<br><br>

---
# 功能描述: <br>
-分割区域多线程(类似folia)(实验性)<br>
-区块加载速度及分配速率优化(已生成的区块)<br>
-bash风格的命令(如/serveroptimize status thread count && serveroptimize status thread balance)<br>
-错误命令/重复命令不加入command_history.txt(默认为关)<br>
-ZSTD/UDP网络完整支持<br>
-CPU线程调度器<br>
<br>
-Regionized multithreading (similar to Folia) (Experimental)<br>
-Chunk loading speed and allocation rate optimization (for already-generated chunks)<br>
-Bash-style commands (e.g., /serveroptimize status thread count && serveroptimize status thread balance)<br>
-Invalid/duplicate commands are not added to command_history.txt (off by default)<br>
-Full ZSTD/UDP network support<br>
-CPU thread scheduler<br>

---
# 模组版本支持: <br>
fabric: 1.21.11<br>

---
如果遇到bug/重要特性被改变, 请提交<br>

Issue If you encounter any bugs or notice changes to key features, please submit an issue.<br><br>

不兼容: C2ME,Accelerated recoiling<br><br>

兼容: Lithium,Carpet,Carpet Extra,Carpet TIS Extra,Carpet AMS Extra,Carpet Org Extra,GugleCarpetAddition<br>

---
FAQ: <br>
1.<br>
A: 未来是否会考虑兼容其他版本? <br>
Q: 对于fabric的其他版本, 我将尽快使用AI进行兼容工作<br><br>
2.<br>
A: 未来是否会考虑兼容其他加载器(Forge,NeoForge)<br>
Q: 在完成大多数原版优化后, 才考虑迁移加载器, 如果你使用Forge,NeoForge, 说明你希望将其与内容模组(如 机械动力及各类附属), 然而兼容它们, 甚至进一步优化它们, 需要大量的时间, 本模组仍然处于早期原版优化+测试阶段<br><br>

FAQ: <br>
1.<br>
A: Will you consider supporting other versions in the future?<br>
Q: For other Fabric versions, I will work on compatibility using AI as soon as possible.<br><br>
2.<br>
A: Will you consider supporting other mod loaders (Forge, NeoForge) in the future?<br>
Q: Migration to other loaders will only be considered after most vanilla optimizations are completed. If you use Forge or NeoForge, it means you want to use it with content mods (such as Create and its various addons). However, making it compatible with them, and even further optimizing them, requires a lot of time. This mod is still in the early vanilla optimization + testing stage.<br>
