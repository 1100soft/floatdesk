//! Dynamic link to the system GStreamer libraries. The host does not vendor
//! GStreamer; the process loads the LGPL libraries already installed on the machine.

use crate::error::{Error, Result};
use libloading::Library;
use std::ffi::{c_char, c_int, c_void, CStr, CString};
use std::sync::OnceLock;

const GST_STATE_NULL: c_int = 1;
const GST_STATE_PLAYING: c_int = 4;
const GST_MAP_READ: u32 = 1;
const GST_MAP_WRITE: u32 = 2;
const GST_MESSAGE_ERROR: u32 = 2;
const GST_CLOCK_TIME_NONE: u64 = u64::MAX;

type GstInit = unsafe extern "C" fn(*mut c_int, *mut *mut *mut c_char);
type ParseLaunch = unsafe extern "C" fn(*const c_char, *mut *mut GError) -> *mut c_void;
type SetState = unsafe extern "C" fn(*mut c_void, c_int) -> c_int;
type GetByName = unsafe extern "C" fn(*mut c_void, *const c_char) -> *mut c_void;
type UtilSet = unsafe extern "C" fn(*mut c_void, *const c_char, *const c_char);
type BufferAlloc = unsafe extern "C" fn(*mut c_void, usize, *mut c_void) -> *mut c_void;
type BufferMap = unsafe extern "C" fn(*mut c_void, *mut GstMapInfo, u32) -> c_int;
type BufferUnmap = unsafe extern "C" fn(*mut c_void, *mut GstMapInfo);
type MapInit = unsafe extern "C" fn(*mut GstMapInfo);
type AppPush = unsafe extern "C" fn(*mut c_void, *mut c_void) -> c_int;
type AppPull = unsafe extern "C" fn(*mut c_void, u64) -> *mut c_void;
type SampleBuffer = unsafe extern "C" fn(*mut c_void) -> *mut c_void;
type MiniUnref = unsafe extern "C" fn(*mut c_void);
type ObjectUnref = unsafe extern "C" fn(*mut c_void);
type GetBus = unsafe extern "C" fn(*mut c_void) -> *mut c_void;
type BusPop = unsafe extern "C" fn(*mut c_void, u64, u32) -> *mut c_void;
type ParseError = unsafe extern "C" fn(*mut c_void, *mut *mut GError, *mut *mut c_char);
type ErrorFree = unsafe extern "C" fn(*mut GError);
type ForceKey = unsafe extern "C" fn(u64, u64, u64, c_int, u32) -> *mut c_void;
type SendEvent = unsafe extern "C" fn(*mut c_void, *mut c_void) -> c_int;

#[repr(C)]
struct GError {
    domain: u32,
    code: i32,
    message: *mut c_char,
}

#[repr(C)]
struct GstMapInfo {
    memory: *mut c_void,
    flags: u32,
    _pad: u32,
    data: *mut u8,
    size: usize,
    maxsize: usize,
    user_data: [*mut c_void; 4],
    reserved: [*mut c_void; 8],
}

struct Api {
    _libs: Vec<Library>,
    gst_init: GstInit,
    parse_launch: ParseLaunch,
    set_state: SetState,
    get_by_name: GetByName,
    util_set: UtilSet,
    buffer_alloc: BufferAlloc,
    buffer_map: BufferMap,
    buffer_unmap: BufferUnmap,
    map_init: MapInit,
    app_push: AppPush,
    app_pull: AppPull,
    sample_buffer: SampleBuffer,
    mini_unref: MiniUnref,
    object_unref: ObjectUnref,
    get_bus: GetBus,
    bus_pop: BusPop,
    parse_error: ParseError,
    error_free: ErrorFree,
    force_key: ForceKey,
    send_event: SendEvent,
}

static API: OnceLock<Api> = OnceLock::new();

fn api() -> Result<&'static Api> {
    if let Some(api) = API.get() {
        return Ok(api);
    }
    let loaded = load_api().map_err(|err| Error::Encoder(err))?;
    let _ = API.set(loaded);
    Ok(API.get().expect("gstreamer api"))
}

fn load_api() -> std::result::Result<Api, String> {
    unsafe {
        let gst = Library::new("libgstreamer-1.0.so.0").map_err(|err| err.to_string())?;
        let app = Library::new("libgstapp-1.0.so.0").map_err(|err| err.to_string())?;
        let video = Library::new("libgstvideo-1.0.so.0").map_err(|err| err.to_string())?;
        let glib = Library::new("libglib-2.0.so.0").map_err(|err| err.to_string())?;
        let gst_init = load_sym(&gst, "gst_init")?;
        let api = Api {
            gst_init,
            parse_launch: load_sym(&gst, "gst_parse_launch")?,
            set_state: load_sym(&gst, "gst_element_set_state")?,
            get_by_name: load_sym(&gst, "gst_bin_get_by_name")?,
            util_set: load_sym(&gst, "gst_util_set_object_arg")?,
            buffer_alloc: load_sym(&gst, "gst_buffer_new_allocate")?,
            buffer_map: load_sym(&gst, "gst_buffer_map")?,
            buffer_unmap: load_sym(&gst, "gst_buffer_unmap")?,
            map_init: load_sym(&gst, "gst_map_info_init")?,
            mini_unref: load_sym(&gst, "gst_mini_object_unref")?,
            object_unref: load_sym(&gst, "gst_object_unref")?,
            get_bus: load_sym(&gst, "gst_element_get_bus")?,
            bus_pop: load_sym(&gst, "gst_bus_timed_pop_filtered")?,
            parse_error: load_sym(&gst, "gst_message_parse_error")?,
            app_push: load_sym(&app, "gst_app_src_push_buffer")?,
            app_pull: load_sym(&app, "gst_app_sink_try_pull_sample")?,
            sample_buffer: load_sym(&gst, "gst_sample_get_buffer")?,
            error_free: load_sym(&glib, "g_error_free")?,
            force_key: load_sym(&video, "gst_video_event_new_downstream_force_key_unit")?,
            send_event: load_sym(&gst, "gst_element_send_event")?,
            _libs: vec![gst, app, video, glib],
        };
        (api.gst_init)(std::ptr::null_mut(), std::ptr::null_mut());
        Ok(api)
    }
}

unsafe fn load_sym<T>(lib: &Library, name: &str) -> std::result::Result<T, String>
where
    T: Copy,
{
    let mut bytes = name.as_bytes().to_vec();
    bytes.push(0);
    let symbol = lib
        .get::<T>(&bytes)
        .map_err(|err| format!("{name}: {err}"))?;
    Ok(*symbol)
}

fn cstr(text: &str) -> Result<CString> {
    CString::new(text).map_err(|_| Error::Encoder(format!("interior nul in `{text}`")))
}

fn error_message(api: &Api, error: *mut GError) -> String {
    if error.is_null() {
        return "unknown gstreamer error".into();
    }
    unsafe {
        let text = if (*error).message.is_null() {
            "gstreamer error".into()
        } else {
            CStr::from_ptr((*error).message)
                .to_string_lossy()
                .into_owned()
        };
        (api.error_free)(error);
        text
    }
}

pub struct Pipeline {
    api: &'static Api,
    pipeline: *mut c_void,
    src: *mut c_void,
    enc: *mut c_void,
    sink: *mut c_void,
}

unsafe impl Send for Pipeline {}

impl Pipeline {
    pub fn launch(description: &str) -> Result<Self> {
        let api = api()?;
        let desc = cstr(description)?;
        unsafe {
            let mut error = std::ptr::null_mut();
            let pipeline = (api.parse_launch)(desc.as_ptr(), &mut error);
            if pipeline.is_null() {
                return Err(Error::Encoder(error_message(api, error)));
            }
            if !error.is_null() {
                (api.error_free)(error);
            }
            let src = named(api, pipeline, "src")?;
            let enc = named(api, pipeline, "enc")?;
            let sink = named(api, pipeline, "sink")?;
            Ok(Self {
                api,
                pipeline,
                src,
                enc,
                sink,
            })
        }
    }

    pub fn set_arg(&self, element: Element, name: &str, value: &str) -> Result<()> {
        let target = self.element(element);
        let name = cstr(name)?;
        let value = cstr(value)?;
        unsafe { (self.api.util_set)(target, name.as_ptr(), value.as_ptr()) };
        Ok(())
    }

    pub fn play(&self) -> Result<()> {
        unsafe {
            let ret = (self.api.set_state)(self.pipeline, GST_STATE_PLAYING);
            if ret == 0 {
                let detail = self.bus_error(200_000_000);
                return Err(Error::Encoder(
                    detail.unwrap_or_else(|| "pipeline failed to play".into()),
                ));
            }
            if let Some(detail) = self.bus_error(0) {
                return Err(Error::Encoder(detail));
            }
            Ok(())
        }
    }

    pub fn push(&self, bytes: &[u8]) -> Result<()> {
        unsafe {
            let buffer =
                (self.api.buffer_alloc)(std::ptr::null_mut(), bytes.len(), std::ptr::null_mut());
            if buffer.is_null() {
                return Err(Error::Encoder("failed to allocate a buffer".into()));
            }
            let mut info = std::mem::zeroed::<GstMapInfo>();
            (self.api.map_init)(&mut info);
            if (self.api.buffer_map)(buffer, &mut info, GST_MAP_WRITE) == 0 || info.data.is_null() {
                (self.api.mini_unref)(buffer);
                return Err(Error::Encoder("failed to map an input buffer".into()));
            }
            let size = info.size.min(bytes.len());
            std::ptr::copy_nonoverlapping(bytes.as_ptr(), info.data, size);
            (self.api.buffer_unmap)(buffer, &mut info);
            let flow = (self.api.app_push)(self.src, buffer);
            if flow != 0 {
                return Err(Error::Encoder(format!("appsrc push returned {flow}")));
            }
            Ok(())
        }
    }

    pub fn pull(&self, timeout_ns: u64) -> Result<Option<Vec<u8>>> {
        unsafe {
            let sample = (self.api.app_pull)(self.sink, timeout_ns);
            if sample.is_null() {
                return Ok(None);
            }
            let buffer = (self.api.sample_buffer)(sample);
            if buffer.is_null() {
                (self.api.mini_unref)(sample);
                return Ok(None);
            }
            let mut info = std::mem::zeroed::<GstMapInfo>();
            (self.api.map_init)(&mut info);
            if (self.api.buffer_map)(buffer, &mut info, GST_MAP_READ) == 0 || info.data.is_null() {
                (self.api.mini_unref)(sample);
                return Err(Error::Encoder("failed to map an encoded buffer".into()));
            }
            let bytes = std::slice::from_raw_parts(info.data, info.size).to_vec();
            (self.api.buffer_unmap)(buffer, &mut info);
            (self.api.mini_unref)(sample);
            Ok(Some(bytes))
        }
    }

    pub fn force_keyframe(&self) {
        unsafe {
            let event = (self.api.force_key)(
                GST_CLOCK_TIME_NONE,
                GST_CLOCK_TIME_NONE,
                GST_CLOCK_TIME_NONE,
                1,
                0,
            );
            if !event.is_null() {
                let sent = (self.api.send_event)(self.enc, event);
                if sent == 0 {
                    tracing::debug!("encoder ignored the keyframe request");
                }
            }
        }
    }

    fn element(&self, element: Element) -> *mut c_void {
        match element {
            Element::Source => self.src,
            Element::Encoder => self.enc,
        }
    }

    fn bus_error(&self, timeout_ns: u64) -> Option<String> {
        unsafe {
            let bus = (self.api.get_bus)(self.pipeline);
            if bus.is_null() {
                return None;
            }
            let message = (self.api.bus_pop)(bus, timeout_ns, GST_MESSAGE_ERROR);
            (self.api.object_unref)(bus);
            if message.is_null() {
                return None;
            }
            let mut error = std::ptr::null_mut();
            (self.api.parse_error)(message, &mut error, std::ptr::null_mut());
            (self.api.mini_unref)(message);
            Some(error_message(self.api, error))
        }
    }
}

impl Drop for Pipeline {
    fn drop(&mut self) {
        unsafe {
            (self.api.set_state)(self.pipeline, GST_STATE_NULL);
            (self.api.object_unref)(self.src);
            (self.api.object_unref)(self.enc);
            (self.api.object_unref)(self.sink);
            (self.api.object_unref)(self.pipeline);
        }
    }
}

pub enum Element {
    Source,
    Encoder,
}

unsafe fn named(api: &Api, pipeline: *mut c_void, name: &str) -> Result<*mut c_void> {
    let c_name = cstr(name)?;
    let element = (api.get_by_name)(pipeline, c_name.as_ptr());
    if element.is_null() {
        (api.object_unref)(pipeline);
        Err(Error::Encoder(format!(
            "pipeline has no element named {name}"
        )))
    } else {
        Ok(element)
    }
}
