package mod;

import de.komoinen.Module;
import de.komoinen.ModuleInfo;
import de.komoinen.ModuleInstance;

public class ModuleObj implements ModuleInstance {
    @Override
    public void preinit(ModuleInfo module) {
        System.out.println("Module created; preinit!");
    }

    @Override
    public void postinit(Module module) {
        System.out.println("Module bound; postinit!");
    }
}
