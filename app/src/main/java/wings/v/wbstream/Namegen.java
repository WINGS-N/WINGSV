package wings.v.wbstream;

import java.security.SecureRandom;

/**
 * Java port of {@code internal/namegen} from vk-turn-proxy. Generates a random
 * VK-style Russian display name (first name, optionally with a surname adjusted
 * for grammatical gender) so wb-stream peers don't reveal that the participant
 * is a {@code wings.v} client by their LiveKit display name. Server-side
 * vk-turn-proxy uses the same lists when its display name flag is empty —
 * keeping client and server symmetric prevents WINGSV from sticking out as a
 * fixed-string outlier in a roomful of namegen'd participants.
 */
@SuppressWarnings("PMD.CommentRequired")
public final class Namegen {

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final String[] MALE_FIRST_NAMES = {
        "Александр",
        "Алексей",
        "Андрей",
        "Антон",
        "Арсений",
        "Артур",
        "Артём",
        "Богдан",
        "Валерий",
        "Василий",
        "Виктор",
        "Владислав",
        "Глеб",
        "Григорий",
        "Даниил",
        "Денис",
        "Дмитрий",
        "Евгений",
        "Егор",
        "Иван",
        "Игорь",
        "Илья",
        "Кирилл",
        "Леонид",
        "Максим",
        "Марк",
        "Матвей",
        "Михаил",
        "Никита",
        "Николай",
        "Олег",
        "Павел",
        "Пётр",
        "Роман",
        "Руслан",
        "Сергей",
        "Станислав",
        "Тимофей",
        "Фёдор",
    };

    private static final String[] FEMALE_FIRST_NAMES = {
        "Алина",
        "Алёна",
        "Анастасия",
        "Ангелина",
        "Анна",
        "Вера",
        "Вероника",
        "Виктория",
        "Дарья",
        "Ева",
        "Екатерина",
        "Елена",
        "Елизавета",
        "Ирина",
        "Кира",
        "Кристина",
        "Ксения",
        "Любовь",
        "Маргарита",
        "Марина",
        "Мария",
        "Милана",
        "Надежда",
        "Наталья",
        "Ольга",
        "Полина",
        "Светлана",
        "София",
        "Татьяна",
        "Юлия",
        "Яна",
    };

    private static final String[] LAST_NAMES = {
        "Алексеев",
        "Андреев",
        "Антонов",
        "Баранов",
        "Белов",
        "Белый",
        "Бельский",
        "Беляев",
        "Борисов",
        "Васильев",
        "Великий",
        "Волков",
        "Воробьёв",
        "Григорьев",
        "Давыдов",
        "Егоров",
        "Жуков",
        "Зайцев",
        "Захаров",
        "Иванов",
        "Калинин",
        "Ковалёв",
        "Козлов",
        "Комаров",
        "Крамской",
        "Кузнецов",
        "Кузьмин",
        "Лебедев",
        "Макаров",
        "Медведев",
        "Михайлов",
        "Морозов",
        "Никитин",
        "Николаев",
        "Новиков",
        "Орлов",
        "Островский",
        "Павлов",
        "Петров",
        "Покровский",
        "Попов",
        "Раевский",
        "Романов",
        "Семёнов",
        "Сергеев",
        "Смирнов",
        "Соколов",
        "Соловьёв",
        "Степанов",
        "Тарасов",
        "Титов",
        "Толстой",
        "Трубецкой",
        "Филиппов",
        "Фролов",
        "Фёдоров",
        "Чайковский",
        "Черный",
        "Яковлев",
    };

    private Namegen() {}

    public static String generate() {
        boolean female = RANDOM.nextInt(2) == 0;
        String firstName = female
            ? FEMALE_FIRST_NAMES[RANDOM.nextInt(FEMALE_FIRST_NAMES.length)]
            : MALE_FIRST_NAMES[RANDOM.nextInt(MALE_FIRST_NAMES.length)];
        if (RANDOM.nextFloat() < 0.3f) {
            return firstName;
        }
        String lastName = LAST_NAMES[RANDOM.nextInt(LAST_NAMES.length)];
        if (female) {
            lastName = toFemaleSurname(lastName);
        }
        return firstName + " " + lastName;
    }

    private static String toFemaleSurname(String surname) {
        if (surname.endsWith("ий") || surname.endsWith("ый") || surname.endsWith("ой")) {
            return surname.substring(0, surname.length() - 2) + "ая";
        }
        if (
            surname.endsWith("ов") ||
            surname.endsWith("ев") ||
            surname.endsWith("ин") ||
            surname.endsWith("ын") ||
            surname.endsWith("ёв")
        ) {
            return surname + "а";
        }
        return surname;
    }
}
