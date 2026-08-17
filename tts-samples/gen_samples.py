#!/usr/bin/env python3
"""Generate voice sample clips from Edge TTS voices for user selection."""
import asyncio, os, sys

import edge_tts

OUT = os.path.dirname(os.path.abspath(__file__))
os.makedirs(OUT, exist_ok=True)

# Realistic novel-style Chinese sentences (one long, one with code-switching)
SENTENCES = {
    "novel": "夜已经很深了，窗外的风呼呼地刮着，我翻开那本泛黄的书，一页一页地读下去，仿佛又回到了许多年前的故乡。",
    "mixed": "他看了一眼手机上的时间，下午三点四十五分，然后转身对助手说：马上订明天早上八点去上海的机票。",
}

VOICES = [
    "zh-CN-XiaoxiaoNeural",   # 晓晓: warm female, most popular
    "zh-CN-XiaoyiNeural",     # 晓伊: young female
    "zh-CN-XiaobeiNeural",    # 晓北: mature female, news-style
    "zh-CN-YunxiNeural",      # 云希: young male, lively
    "zh-CN-YunjianNeural",    # 云健: mature male, documentary
    "zh-CN-YunyangNeural",    # 云扬: professional male, news
]

async def main():
    tasks = []
    for v in VOICES:
        for name, text in SENTENCES.items():
            out = os.path.join(OUT, f"{v}_{name}.mp3")
            t = edge_tts.Communicate(text, v)
            tasks.append((out, t.save(out)))
    for out, fut in tasks:
        await fut
        print(f"OK {out}", flush=True)
    print("ALL_DONE")

asyncio.run(main())
