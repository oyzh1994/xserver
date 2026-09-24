// On-screen windows with owner, title and bounds - no permission needed (CGWindowList).
#include <ApplicationServices/ApplicationServices.h>
#include <stdio.h>
int main(void) {
    CFArrayRef list = CGWindowListCopyWindowInfo(kCGWindowListOptionOnScreenOnly, kCGNullWindowID);
    for (CFIndex i = 0; i < CFArrayGetCount(list); i++) {
        CFDictionaryRef w = CFArrayGetValueAtIndex(list, i);
        int layer = 0;
        CFNumberGetValue(CFDictionaryGetValue(w, kCGWindowLayer), kCFNumberIntType, &layer);
        if (layer != 0) continue;
        char owner[128] = "?", name[256] = "";
        CFStringGetCString(CFDictionaryGetValue(w, kCGWindowOwnerName), owner, sizeof owner, kCFStringEncodingUTF8);
        CFStringRef n = CFDictionaryGetValue(w, kCGWindowName);
        if (n) CFStringGetCString(n, name, sizeof name, kCFStringEncodingUTF8);
        CGRect r;
        CGRectMakeWithDictionaryRepresentation(CFDictionaryGetValue(w, kCGWindowBounds), &r);
        printf("%s | %s | %.0f,%.0f %.0fx%.0f\n", owner, name, r.origin.x, r.origin.y, r.size.width, r.size.height);
    }
    CFRelease(list);
    return 0;
}
