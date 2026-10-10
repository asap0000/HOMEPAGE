#!/usr/bin/env python3
"""保管庫へ取り込む窓。USB 接続だけでは取り込みを始めない。"""
import argparse
import datetime as dt
from pathlib import Path
import queue
import threading
import time

import buscourse_archive as archive


def gb(size):
    return f'{size / 1_000_000_000:.2f}GB'


def minutes(seconds):
    return f'{max(1, round(seconds / 60))}分'


NOT_FOUND = 'USB の線・スマホの「USB デバッグを許可」・画面のロックを確かめてください'


def view_state(kind, **values):
    if kind == 'searching':
        return '探しています', 'USB でつないで、スマホの画面のロックを外してください', None
    if kind == 'found':
        pending = f"\n書き出し中 {values['pending']}本" if values.get('pending') else ''
        return '見つかりました', (f"{values['model']}\n取り込み待ち {values['total']}本・{gb(values['bytes'])}"
                             f"{pending}\n保管庫: {values['root']}"), '取り込む'
    if kind == 'running':
        remaining = values.get('remaining_seconds')
        eta = f'残り約{max(1, round(remaining / 60))}分' if remaining is not None else '残り時間を計算中'
        started = values.get('startedAt')
        when = dt.datetime.fromtimestamp(started / 1000).strftime('%Y-%m-%d %H:%M') if started else values.get('current', '')
        distance = values.get('distanceM')
        distance_text = f'・{distance / 1000:.1f}km' if isinstance(distance, (int, float)) else ''
        return '取り込み中', (f"{values.get('ingested', 0)}/{values['total']}本・{gb(values.get('ingested_bytes', 0))}"
                         f" / {gb(values['total_bytes'])}\n{eta}\nいまの走行: {when}{distance_text}"
                         '\nUSB を抜かないでください。閉じても入れた分は残ります'), None
    if kind == 'done':
        return '終わりました', (f"{values['ingested']}本を保管庫に入れました（{gb(values['bytes'])}・{minutes(values['elapsed'])}）"
                          '\nスマホに受領票を渡しました\nUSB を抜いてかまいません'), '閉じる'
    if kind == 'partial':
        return '一部が合わない／引き取れなかった', (f"{values.get('ingested', 0)}本を保管庫に入れました\n"
                                      f"合わなかった {values['mismatch']}本・引き取れなかった {values['not_pulled']}本"
                                      '\nスマホに残してあります。もう一度開くとやり直します'
                                      '\nUSB を抜いてかまいません'), '閉じる'
    if kind == 'pending':
        return '書き出し中', (f"スマホで書き出し中の走行が {values['count']}本あります。"
                         "終わってから『もう一度探す』を押してください"), 'もう一度探す'
    if kind == 'empty':
        return '取り込む走行はありません', "スマホで先に『保管庫へ書き出す』を押してください", 'もう一度探す'
    return 'スマホが見つかりません', values.get('reason', NOT_FOUND), 'もう一度探す'


def selftest():
    assert view_state('searching')[0] == '探しています'
    assert view_state('found', model='合成機種', total=1, bytes=1000, root='vault')[2] == '取り込む'
    assert 'USB を抜かないで' in view_state('running', total=1, total_bytes=1000)[1]
    assert 'USB を抜いてかまいません' in view_state('done', ingested=1, bytes=1000, elapsed=1)[1]
    assert 'スマホに残して' in view_state('partial', mismatch=1, not_pulled=0)[1]
    assert view_state('missing')[2] == 'もう一度探す'


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', type=Path, default=Path(r'D:\ishix\BusCourse\保管庫'))
    parser.add_argument('--adb')
    parser.add_argument('--selftest', action='store_true')
    args = parser.parse_args(argv)
    if args.selftest:
        selftest()
        print('GUI 状態テスト: OK')
        return 0

    import tkinter as tk
    from tkinter import messagebox, simpledialog, ttk

    window = tk.Tk()
    window.title('保管庫へ取り込む')
    window.geometry('640x390')
    window.minsize(560, 330)
    window.option_add('*Font', ('Yu Gothic UI', 12))
    title = ttk.Label(window, font=('Yu Gothic UI', 18, 'bold'))
    title.pack(anchor='w', padx=24, pady=(24, 12))
    body = ttk.Label(window, wraplength=570, justify='left', font=('Yu Gothic UI', 12))
    body.pack(anchor='w', padx=24, pady=8)
    bar = ttk.Progressbar(window, length=560, mode='determinate')
    bar.pack(padx=24, pady=16)
    button = ttk.Button(window)
    button.pack(padx=24, pady=8, anchor='e')
    inbox = queue.Queue()
    cancel = threading.Event()
    state = {'kind': 'searching', 'selected': None, 'adb': None, 'working': False, 'closing': False}

    def show(kind, **values):
        state['kind'] = kind
        heading, message, action = view_state(kind, **values)
        title.config(text=heading)
        body.config(text=message)
        if kind == 'running':
            total = values.get('total_bytes', 0)
            bar.config(value=(values.get('ingested_bytes', 0) * 100 / total if total else 0))
        else:
            bar.config(value=0)
        if action:
            button.config(text=action, command=(start if kind == 'found' else
                                                 window.destroy if kind in ('done', 'partial') else search))
            button.pack(padx=24, pady=8, anchor='e')
        else:
            button.pack_forget()

    def search():
        show('searching')
        def worker():
            try:
                adb, serials = archive.choose_adb(args.adb)
                selected = archive.discover(adb, serials, lambda options: options)
                if not selected:
                    inbox.put(('missing', NOT_FOUND))
                    return
                for item in selected if isinstance(selected, list) else ([selected] if selected else []):
                    item['total_bytes'] = sum(archive.remote_size(adb, item['serial'], n) for n in item['ready'])
                inbox.put(('found', adb, selected))
            except Exception as error:
                inbox.put(('missing', str(error)))
        threading.Thread(target=worker, daemon=True).start()

    def start():
        selected = state['selected']
        if not selected or state['working']:
            return
        state['working'] = True
        cancel.clear()
        started = time.monotonic()
        show('running', total=len(selected['ready']), total_bytes=state['total_bytes'])
        def worker():
            try:
                args.root.mkdir(parents=True, exist_ok=True)
                for category in ('安定前', '安定後'):
                    (args.root / category).mkdir(exist_ok=True)
                result = archive.pull(args.root, selected['serial'], state['adb'], cancel=cancel,
                                      progress=lambda info: inbox.put(('progress', info)), model=selected['model'])
                inbox.put(('complete', result, time.monotonic() - started))
            except Exception as error:
                inbox.put(('missing', str(error)))
        threading.Thread(target=worker, daemon=True).start()

    def poll():
        try:
            while True:
                item = inbox.get_nowait()
                if item[0] == 'found':
                    adb, selected = item[1:]
                    if isinstance(selected, list):
                        labels = '\n'.join(f'{i + 1}: {row["model"]}（{len(row["ready"])}本）'
                                           for i, row in enumerate(selected))
                        choice = simpledialog.askinteger('スマホを選ぶ', labels + '\n番号を選んでください',
                                                         minvalue=1, maxvalue=len(selected), parent=window)
                        selected = selected[choice - 1] if choice else None
                    state['adb'], state['selected'] = adb, selected
                    if selected is None:
                        show('missing', reason='スマホを選んでください。')
                    elif selected['ready']:
                        state['total_bytes'] = selected['total_bytes']
                        show('found', model=selected['model'], total=len(selected['ready']),
                             bytes=state['total_bytes'], pending=len(selected['pending']), root=args.root)
                    elif selected['pending']:
                        show('pending', count=len(selected['pending']))
                    else:
                        show('empty')
                elif item[0] == 'progress':
                    show('running', **item[1])
                elif item[0] == 'complete':
                    state['working'] = False
                    if state['closing']:
                        window.destroy(); return
                    result, elapsed = item[1:]
                    if result['mismatch'] or result['not_pulled']:
                        show('partial', **result)
                    else:
                        show('done', elapsed=elapsed, **result)
                elif item[0] == 'missing':
                    state['working'] = False
                    if state['closing']:
                        window.destroy(); return
                    show('missing', reason=item[1])
        except queue.Empty:
            pass
        window.after(100, poll)

    def close():
        if state['working']:
            if messagebox.askyesno('取り込みをやめる',
                                   'やめますか？入れた分は保管庫に残り、次に開くと残りから続きます', parent=window):
                state['closing'] = True
                cancel.set()
                body.config(text='いまの走行の写しを片付けています。少しお待ちください。')
        else:
            window.destroy()

    window.protocol('WM_DELETE_WINDOW', close)
    search()
    poll()
    window.mainloop()
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
