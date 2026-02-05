import asyncio
import io
import os
from flask import Flask, request, jsonify, send_file
from telethon import TelegramClient, events
from telethon.tl.types import PeerUser, PeerChat, PeerChannel, MessageMediaPhoto
from PIL import Image
import config

app = Flask(__name__)
client = TelegramClient('session_name', config.API_ID, config.API_HASH)

# Глобальное состояние для хранения кода подтверждения (упрощенно для MVP)
phone_code_hashes = {}

async def get_client():
    if not client.is_connected():
        await client.connect()
    return client

@app.before_request
def log_request_info():
    print(f"\n[>>>] Incoming {request.method} request to {request.path}")
    print(f"      From IP: {request.remote_addr}")
    print(f"      Args: {dict(request.args)}")

@app.after_request
def add_header(response):
    response.headers["Connection"] = "close"
    response.headers["bypass-tunnel-reminder"] = "true"
    return response

@app.route('/')
def index():
    return "Server is RUNNING and REACHABLE!"

@app.route('/api/chats', methods=['GET'])
@app.route('/chats', methods=['GET'])
async def get_chats():
    c = await get_client()
    if not await c.is_user_authorized():
        return jsonify({"status": "unauthorized"})
    
    dialogs = await c.get_dialogs(limit=20)
    result = []
    for d in dialogs:
        result.append({
            "id": d.id,
            "name": d.name or "Unknown",
            "unread": d.unread_count
        })
    return jsonify(result)

@app.route('/api/messages', methods=['GET'])
@app.route('/messages', methods=['GET'])
async def get_messages():
    chat_id = int(request.args.get('id'))
    c = await get_client()
    if not await c.is_user_authorized():
        return jsonify({"status": "unauthorized"})
    
    messages = await c.get_messages(chat_id, limit=20)
    result = []
    for m in messages:
        sender = await m.get_sender()
        sender_name = getattr(sender, 'first_name', 'System') or "System"
        
        msg_data = {
            "id": m.id,
            "from": sender_name,
            "text": m.text or "",
            "has_photo": 1 if isinstance(m.media, MessageMediaPhoto) else 0,
            "reactions": ""
        }
        
        # Получаем реакции
        if m.reactions:
            reac_list = []
            for r in m.reactions.results:
                try:
                    # В Telethon 1.x реакции могут быть разными типами
                    emo = getattr(r.reaction, 'emoticon', '')
                    if emo:
                        reac_list.append(emo + ":" + str(r.count))
                except: continue
            msg_data["reactions"] = " ".join(reac_list)
        
        # Поддержка ответов (replies)
        if m.is_reply:
            reply_to = await m.get_reply_message()
            if reply_to:
                msg_data["reply_to"] = (reply_to.text[:20] + "...") if reply_to.text else "Media"

        if msg_data["text"] or msg_data["has_photo"]:
            result.append(msg_data)
            
    return jsonify(result[::-1])

@app.route('/api/photo', methods=['GET'])
@app.route('/photo', methods=['GET'])
async def get_photo():
    chat_id = int(request.args.get('chat_id'))
    msg_id = int(request.args.get('msg_id'))
    c = await get_client()
    
    msg = await c.get_messages(chat_id, ids=msg_id)
    if not msg or not msg.photo:
        return "No photo", 404
    
    # Скачиваем в память
    photo_bytes = await c.download_media(msg.photo, file=bytes)
    
    # Сжимаем для Nokia (240px ширина)
    img = Image.open(io.BytesIO(photo_bytes))
    ratio = 240 / float(img.size[0])
    height = int(float(img.size[1]) * float(ratio))
    img = img.resize((240, height), Image.Resampling.LANCZOS)
    
    img_io = io.BytesIO()
    img.save(img_io, 'JPEG', quality=60)
    img_io.seek(0)
    
    return send_file(img_io, mimetype='image/jpeg')

@app.route('/api/send', methods=['GET', 'POST'])
@app.route('/send', methods=['GET', 'POST'])
async def send_message():
    chat_id = int(request.args.get('id'))
    text = request.args.get('text')
    c = await get_client()
    if not await c.is_user_authorized():
        return jsonify({"status": "unauthorized"})
    
    await c.send_message(chat_id, text)
    return jsonify({"status": "ok"})

@app.route('/api/react', methods=['GET'])
@app.route('/react', methods=['GET'])
async def react_message():
    chat_id = int(request.args.get('chat_id'))
    msg_id = int(request.args.get('msg_id'))
    emoji = request.args.get('emoji')
    c = await get_client()
    if not await c.is_user_authorized():
        return jsonify({"status": "unauthorized"})
    
    try:
        from telethon.tl.functions.messages import SendReactionRequest
        from telethon.tl.types import ReactionEmoji
        await c(SendReactionRequest(
            peer=chat_id,
            msg_id=msg_id,
            reaction=[ReactionEmoji(emoticon=emoji)]
        ))
        return jsonify({"status": "ok"})
    except Exception as e:
        return jsonify({"status": "error", "message": str(e)})

@app.route('/api/auth/send_code', methods=['GET'])
@app.route('/auth/send_code', methods=['GET'])
async def send_code():
    phone = request.args.get('phone')
    if not phone:
        return jsonify({"status": "error", "message": "No phone provided"})
    
    print(f"\n[!] incoming request from phone: {phone}")
    
    try:
        c = await get_client()
        print("[-] Connected to Telegram, sending request...")
        res = await c.send_code_request(phone)
        phone_code_hashes[phone] = res.phone_code_hash
        print(f"[+] SUCCESS! Code sent. Hash: {res.phone_code_hash}")
        return jsonify({"status": "ok", "message": "Code sent"})
    except Exception as e:
        print(f"[X] TELEGRAM ERROR: {e}")
        return jsonify({"status": "error", "message": str(e)})

@app.route('/api/auth/login', methods=['GET'])
@app.route('/auth/login', methods=['GET'])
async def login():
    phone = request.args.get('phone')
    code = request.args.get('code')
    password = request.args.get('password') # 2FA if needed
    
    c = await get_client()
    from telethon.errors import SessionPasswordNeededError
    try:
        if phone in phone_code_hashes:
            try:
                await c.sign_in(phone, code, phone_code_hash=phone_code_hashes[phone])
                return jsonify({"status": "ok", "message": "Logged in"})
            except SessionPasswordNeededError:
                if password:
                    await c.sign_in(password=password)
                    return jsonify({"status": "ok", "message": "Logged in with 2FA"})
                else:
                    return jsonify({"status": "error", "message": "2FA_REQUIRED"})
        else:
            return jsonify({"status": "error", "message": "Call send_code first"})
    except Exception as e:
        return jsonify({"status": "error", "message": str(e)})

if __name__ == '__main__':
    if config.USE_NGROK and config.NGROK_AUTHTOKEN:
        from pyngrok import ngrok
        ngrok.set_auth_token(config.NGROK_AUTHTOKEN)
        public_url = ngrok.connect(config.PORT).public_url
        print(f"\n[!] NGROK ONLINE: {public_url}")
        print(f"[!] Введите этот адрес в Nokia: {public_url}\n")
    
    # Flask 3.0+ поддерживает асинхронный запуск напрямую
    app.run(host='0.0.0.0', port=config.PORT, debug=True, use_reloader=False)
