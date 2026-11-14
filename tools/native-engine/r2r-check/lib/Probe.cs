namespace ProbeLib
{
    public static class Probe
    {
        public static int Answer(int x) => x * 6;

        // What Godot's exported builds call: an [UnmanagedCallersOnly] entry reached through hostfxr's
        // load_assembly_and_get_function_pointer on the assembly itself (see fxrhost.py).
        [System.Runtime.InteropServices.UnmanagedCallersOnly]
        public static int Entry(int x) => Answer(x) + System.Linq.Enumerable.Sum(System.Linq.Enumerable.Range(1, 3)) - 6;
    }
}
