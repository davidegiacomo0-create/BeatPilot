#!/usr/bin/env python3
"""Optional Linux/Mesa check: execute the app's actual video shaders and verify RGBA orientation.

This tests GL rendering, not Android MediaCodec, MediaStore, or physical-device performance.
"""
import ctypes as C
import ctypes.util
import json
import pathlib
import re

root = pathlib.Path(__file__).resolve().parents[1]
source = (root / 'app/src/main/java/it/dave/beatpilot/EncoderSurface.java').read_text()
def string_constant(name):
    expression = re.search(rf'static final String {name}\s*=\s*(.*?);\s*\n', source, re.S).group(1)
    return ''.join(json.loads(s) for s in re.findall(r'"(?:[^"\\]|\\.)*"', expression)).encode()
quad = [float(v) for v in re.search(r'float\[\] QUAD\s*=\s*\{([^}]+)\}', source).group(1).split(',')]
egl = C.CDLL(ctypes.util.find_library('EGL') or 'libEGL.so.1')
egl.eglGetProcAddress.argtypes = [C.c_char_p]; egl.eglGetProcAddress.restype = C.c_void_p
def proc(name, result, *args):
    address = egl.eglGetProcAddress(name.encode())
    if not address:
        raise RuntimeError('Missing EGL/GL entry: ' + name)
    return C.CFUNCTYPE(result, *args)(address)
uint, integer, ptr = C.c_uint, C.c_int, C.c_void_p
display = proc('eglGetPlatformDisplayEXT', ptr, uint, ptr, ptr)(0x31DD, None, None)
major, minor = integer(), integer()
assert proc('eglInitialize', uint, ptr, ptr, ptr)(display, C.byref(major), C.byref(minor))
assert proc('eglBindAPI', uint, uint)(0x30A0)
attributes = (integer * 15)(0x3024,8,0x3023,8,0x3022,8,0x3021,8,0x3033,1,0x3040,4,0x3025,0,0x3038)
config, count = ptr(), integer()
assert proc('eglChooseConfig',uint,ptr,ptr,ptr,integer,ptr)(display,attributes,C.byref(config),1,C.byref(count)) and count.value
context = proc('eglCreateContext',ptr,ptr,ptr,ptr,ptr)(display,config,None,(integer*3)(0x3098,2,0x3038))
w, h = 4, 6
surface = proc('eglCreatePbufferSurface',ptr,ptr,ptr,ptr)(display,config,(integer*5)(0x3057,w,0x3056,h,0x3038))
assert context and surface
assert proc('eglMakeCurrent',uint,ptr,ptr,ptr,ptr)(display,surface,surface,context)
def shader(kind, code):
    obj = proc('glCreateShader',uint,uint)(kind)
    text = C.c_char_p(code)
    proc('glShaderSource',None,uint,integer,ptr,ptr)(obj,1,C.byref(text),None)
    proc('glCompileShader',None,uint)(obj)
    okay = integer(); proc('glGetShaderiv',None,uint,uint,ptr)(obj,0x8B81,C.byref(okay))
    if not okay.value:
        error = C.create_string_buffer(2048)
        proc('glGetShaderInfoLog',None,uint,integer,ptr,ptr)(obj,2048,None,error)
        raise AssertionError(error.value.decode())
    return obj
program = proc('glCreateProgram',uint)()
for obj in [shader(0x8B31,string_constant('VERTEX')),shader(0x8B30,string_constant('FRAGMENT'))]:
    proc('glAttachShader',None,uint,uint)(program,obj)
proc('glLinkProgram',None,uint)(program)
linked = integer(); proc('glGetProgramiv',None,uint,uint,ptr)(program,0x8B82,C.byref(linked)); assert linked.value
proc('glUseProgram',None,uint)(program)
texture = uint(); proc('glGenTextures',None,integer,ptr)(1,C.byref(texture))
proc('glBindTexture',None,uint,uint)(0x0DE1,texture)
for key,value in [(0x2801,0x2600),(0x2800,0x2600),(0x2802,0x812F),(0x2803,0x812F)]:
    proc('glTexParameteri',None,uint,uint,integer)(0x0DE1,key,value)
pixels = bytes(channel for y in range(h) for x in range(w) for channel in [y*40,x*60,(y+x)*16,255])
raw = (C.c_ubyte * len(pixels)).from_buffer_copy(pixels)
proc('glTexImage2D',None,uint,integer,integer,integer,integer,integer,uint,uint,ptr)(0x0DE1,0,0x1908,w,h,0,0x1908,0x1401,None)
proc('glTexSubImage2D',None,uint,integer,integer,integer,integer,integer,uint,uint,ptr)(0x0DE1,0,0,0,w,h,0x1908,0x1401,raw)
vertices = (C.c_float * len(quad))(*quad)
for name,offset in [(b'position',0),(b'texCoord',8)]:
    loc = proc('glGetAttribLocation',integer,uint,C.c_char_p)(program,name); assert loc>=0
    proc('glVertexAttribPointer',None,uint,integer,uint,C.c_ubyte,integer,ptr)(loc,2,0x1406,0,16,C.byref(vertices,offset))
    proc('glEnableVertexAttribArray',None,uint)(loc)
loc = proc('glGetUniformLocation',integer,uint,C.c_char_p)(program,b'image')
proc('glUniform1i',None,integer,integer)(loc,0)
proc('glViewport',None,integer,integer,integer,integer)(0,0,w,h)
proc('glDrawArrays',None,uint,integer,integer)(0x0005,0,4)
result = (C.c_ubyte * len(pixels))()
proc('glReadPixels',None,integer,integer,integer,integer,uint,uint,ptr)(0,0,w,h,0x1908,0x1401,result)
assert proc('glGetError',uint)()==0
bottom_up = bytes(result)
top_down = b''.join(bottom_up[y*w*4:(y+1)*w*4] for y in reversed(range(h)))
assert top_down==pixels, 'Rendered RGBA pixels are flipped, reordered, cropped, or changed'
proc('eglMakeCurrent',uint,ptr,ptr,ptr,ptr)(display,None,None,None)
proc('eglDestroySurface',uint,ptr,ptr)(display,surface)
proc('eglDestroyContext',uint,ptr,ptr)(display,context)
proc('eglTerminate',uint,ptr)(display)
print('PASS: actual EncoderSurface GLSL/quad rendered on Mesa EGL; all 24 RGBA pixels preserve orientation and channels.')
print('Android MediaCodec and physical-device latency are not exercised by this check.')
